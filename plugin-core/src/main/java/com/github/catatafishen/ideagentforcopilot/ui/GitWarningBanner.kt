package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JButton

/**
 * Warning banner shown when the project has no git repository or no git remote configured.
 * Warns users that the agent can make destructive edits and version control is important
 * for safe rollbacks. Dismissible per-project via a "don't remind me" checkbox.
 */
class GitWarningBanner(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private companion object {
        const val KEY_DISMISSED = "copilot.gitWarningDismissed"
        val bannerBorder = JBColor(Color(0xC7, 0x22, 0x22), Color(0x99, 0x33, 0x33))
        val bannerBg = JBColor(Color(0xFD, 0xE8, 0xE8), Color(0x3D, 0x20, 0x20))
        val bannerFg = JBColor(Color(0x7A, 0x1B, 0x1B), Color(0xE0, 0x80, 0x80))
    }

    init {
        isVisible = false
        isOpaque = true
        background = bannerBg
        border = JBUI.Borders.compound(
            SideBorder(bannerBorder, SideBorder.BOTTOM),
            JBUI.Borders.empty(6, 8),
        )

        val icon = JBLabel(AllIcons.General.Warning).apply {
            border = JBUI.Borders.emptyRight(6)
        }

        val textLabel = JBLabel().apply {
            foreground = bannerFg
        }

        val dontRemindCheckbox = JBCheckBox("Don't remind me again for this project").apply {
            foreground = bannerFg
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
        }

        val closeButton = JButton("✕").apply {
            foreground = bannerFg
            isOpaque = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Dismiss"
            border = JBUI.Borders.empty(0, 8)
        }

        closeButton.addActionListener {
            if (dontRemindCheckbox.isSelected) {
                PropertiesComponent.getInstance(project).setValue(KEY_DISMISSED, true)
            }
            isVisible = false
            revalidate()
            repaint()
        }

        val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(textLabel, BorderLayout.CENTER)
            add(closeButton, BorderLayout.EAST)
        }

        val bottomRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(22) // align with text past icon
            add(dontRemindCheckbox)
        }

        val rows = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topRow)
            add(bottomRow)
        }
        add(rows, BorderLayout.CENTER)

        // Check git status on a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val warning = checkGitStatus()
            if (warning != null) {
                javax.swing.SwingUtilities.invokeLater {
                    textLabel.text = "<html><b>⚠ No version control detected.</b> $warning " +
                        "The agent can make destructive edits — having git with a remote " +
                        "is important for safe rollbacks.</html>"
                    isVisible = true
                    revalidate()
                    repaint()
                }
            }
        }
    }

    private fun checkGitStatus(): String? {
        if (PropertiesComponent.getInstance(project).getBoolean(KEY_DISMISSED, false)) {
            return null
        }
        val basePath = project.basePath ?: return "No project directory found."
        val dir = java.io.File(basePath)

        // Check if git is available
        if (!isGitInstalled(dir)) {
            return "Git is not installed or not found in PATH."
        }

        // Check if this is a git repository
        if (!isGitRepo(dir)) {
            return "This project is not a git repository."
        }

        // Check if any remote is configured
        if (!hasGitRemote(dir)) {
            return "No git remote is configured — local commits alone may not be enough for recovery."
        }

        return null
    }

    private fun isGitInstalled(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "--version")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isGitRepo(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && output == "true"
        } catch (_: Exception) {
            false
        }
    }

    private fun hasGitRemote(dir: java.io.File): Boolean {
        return try {
            val p = ProcessBuilder("git", "remote")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && output.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
