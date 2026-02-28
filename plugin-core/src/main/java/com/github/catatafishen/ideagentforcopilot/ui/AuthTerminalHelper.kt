package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

private val LOG = Logger.getInstance("AuthTerminalHelper")

/**
 * Opens a named tab in IntelliJ's embedded Terminal tool window and runs [command] once the
 * shell is ready.  Falls back to [onTerminalUnavailable] (called on the EDT) when the terminal
 * plugin class is not on the classpath.
 *
 * Readiness is determined by polling `getProcessTtyConnector()` (non-null == shell connected)
 * rather than a fixed sleep, with a 5-second timeout.
 */
fun runAuthInEmbeddedTerminal(
    project: Project,
    command: String,
    tabName: String,
    onTerminalUnavailable: () -> Unit,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val terminalView = terminalViewClass
                .getMethod("getInstance", Project::class.java)
                .invoke(null, project)

            val widget = terminalViewClass.getMethod(
                "createLocalShellWidget",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(terminalView, project.basePath, tabName, /* requestFocus */ true)

            // Poll until the shell's TtyConnector is ready (max 5 s, checks every 100 ms).
            val getTty = widget.javaClass.getMethod("getProcessTtyConnector")
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                if (getTty.invoke(widget) != null) break
                Thread.sleep(100)
            }

            widget.javaClass.getMethod("executeCommand", String::class.java)
                .invoke(widget, command)
        } catch (e: ClassNotFoundException) {
            LOG.info("Terminal plugin not available, falling back to external terminal")
            SwingUtilities.invokeLater { onTerminalUnavailable() }
        } catch (e: Exception) {
            LOG.warn("Failed to run auth command in embedded terminal", e)
            SwingUtilities.invokeLater { onTerminalUnavailable() }
        }
    }
}
