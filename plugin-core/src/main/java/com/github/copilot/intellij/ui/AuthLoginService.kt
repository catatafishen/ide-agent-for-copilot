package com.github.copilot.intellij.ui

import com.github.copilot.intellij.services.CopilotService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Encapsulates all authentication login logic for Copilot CLI and GitHub CLI.
 * Extracted from AgenticCopilotToolWindowContent to keep the tool window lean.
 */
internal class AuthLoginService(private val project: Project) {

    private companion object {
        private val LOG = Logger.getInstance(AuthLoginService::class.java)
        private const val OS_NAME_PROPERTY = "os.name"
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /** Returns null if Copilot CLI is installed and authenticated, or an error description. */
    fun copilotSetupDiagnostics(): String? = try {
        CopilotService.getInstance(project).getClient().listModels()
        null
    } catch (e: Exception) {
        e.message ?: "Failed to connect to Copilot CLI"
    }

    /** Returns null if GH CLI is installed and authenticated, or an error description. */
    internal fun ghSetupDiagnostics(billing: BillingManager): String? {
        val ghCli = billing.findGhCli()
            ?: return "GitHub CLI (gh) is not installed — it is used to display billing and usage information."
        return if (!billing.isGhAuthenticated(ghCli))
            "Not authenticated with GitHub CLI (gh) — click Sign In in the banner above."
        else null
    }

    /** Returns true when [message] indicates a Copilot CLI authentication failure. */
    fun isAuthenticationError(message: String): Boolean =
        message.contains("auth") ||
            message.contains("Copilot CLI") ||
            message.contains("authenticated")

    // ── Login flows ──────────────────────────────────────────────────────────

    /**
     * Opens a "Copilot Sign In" terminal tab and runs the auth command.
     * The command is taken from the ACP initialize response when available,
     * falling back to `copilot auth login`.
     * Falls back to an external terminal if the embedded terminal plugin is absent.
     */
    fun startCopilotLogin() {
        var command = "copilot auth login"
        try {
            val authMethod = CopilotService.getInstance(project).getClient().authMethod
            if (authMethod?.command != null) {
                val args = authMethod.args?.joinToString(" ") ?: ""
                command = "${authMethod.command} $args".trim()
            }
        } catch (_: Exception) { /* best-effort */
        }

        val resolvedCommand = command
        runAuthInEmbeddedTerminal(project, resolvedCommand, "Copilot Sign In") {
            startCopilotLoginExternal(resolvedCommand)
        }
    }

    /**
     * Opens a "GitHub Sign In" terminal tab and runs `gh auth login`.
     * Falls back to an external terminal if the embedded terminal plugin is absent.
     */
    fun startGhLogin() {
        runAuthInEmbeddedTerminal(project, "gh auth login", "GitHub Sign In") {
            startGhLoginExternal()
        }
    }

    // ── External-terminal fallbacks (used only when terminal plugin is absent) ──

    private fun startCopilotLoginExternal(command: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launchExternalTerminal(command)
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for Copilot auth", e)
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "The IntelliJ Terminal plugin is not available and no external terminal could be opened.\n\n" +
                            "Run manually: $command",
                        "Authentication Setup",
                    )
                }
            }
        }
    }

    private fun startGhLoginExternal() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launchExternalTerminal("gh auth login")
            } catch (e: Exception) {
                LOG.warn("Could not open external terminal for GitHub auth", e)
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "The IntelliJ Terminal plugin is not available and no external terminal could be opened.\n\n" +
                            "Run manually: gh auth login",
                        "GitHub CLI Authentication",
                    )
                }
            }
        }
    }

    private fun launchExternalTerminal(command: String) {
        val os = System.getProperty(OS_NAME_PROPERTY).lowercase()
        when {
            os.contains("win") ->
                ProcessBuilder("cmd", "/c", "start", "cmd", "/k", command).start()

            os.contains("mac") ->
                ProcessBuilder(
                    "osascript", "-e",
                    "tell application \"Terminal\" to do script \"$command\"",
                ).start()

            else ->
                ProcessBuilder(
                    "sh", "-c",
                    "x-terminal-emulator -e '$command' || " +
                        "gnome-terminal -- $command || " +
                        "konsole -e $command || " +
                        "xterm -e $command",
                ).start()
        }
    }
}
