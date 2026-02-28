package com.github.copilot.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Runs an authentication command inside IntelliJ's embedded Terminal tool window.
 * Falls back to an external terminal if the terminal plugin is unavailable.
 *
 * @param command  The shell command to run (e.g. "gh auth login").
 * @param tabName  Label for the terminal tab.
 * @param onTerminalUnavailable  Called on the EDT when the terminal plugin is not present;
 *                               the caller should launch an external terminal or show an error.
 */
fun runAuthInEmbeddedTerminal(
    project: Project,
    command: String,
    tabName: String,
    onTerminalUnavailable: () -> Unit
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val getInstance = terminalViewClass.getMethod("getInstance", Project::class.java)
            val terminalView = getInstance.invoke(null, project)

            val widget = terminalViewClass.getMethod(
                "createLocalShellWidget",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(terminalView, project.basePath, tabName, true)

            // Give the shell a moment to initialise before sending the command
            Thread.sleep(800)

            val executeCommand = widget.javaClass.getMethod("executeCommand", String::class.java)
            executeCommand.invoke(widget, command)
        } catch (e: ClassNotFoundException) {
            LOG.info("Terminal plugin not available, falling back to external terminal")
            SwingUtilities.invokeLater { onTerminalUnavailable() }
        } catch (e: Exception) {
            LOG.warn("Failed to run auth command in embedded terminal", e)
            SwingUtilities.invokeLater { onTerminalUnavailable() }
        }
    }
}

private val LOG = Logger.getInstance("AuthTerminalHelper")
