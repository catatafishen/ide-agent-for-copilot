package com.github.copilot.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AgenticCopilotToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = AgenticCopilotToolWindowContent(project)

        val toolWindowContent = ContentFactory.getInstance().createContent(
            content.getComponent(), "", false
        )
        toolWindow.contentManager.addContent(toolWindowContent)

        toolWindow.setTitleActions(
            listOf(
                object : AnAction("New Chat", "Start a fresh conversation", AllIcons.Actions.Restart) {
                    override fun actionPerformed(e: AnActionEvent) {
                        content.resetSession()
                    }
                },
                object : AnAction("Settings", "Open plugin settings", AllIcons.General.Settings) {
                    override fun actionPerformed(e: AnActionEvent) {
                        content.openSettings()
                    }
                }
            ))

        // Gear menu (⋯ → additional actions)
        val gearGroup = com.intellij.openapi.actionSystem.DefaultActionGroup()
        gearGroup.add(object : com.intellij.openapi.actionSystem.ToggleAction(
            "Use New Chat Pane (V2)",
            "Switch to the web-component-based chat pane",
            null
        ) {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
            override fun isSelected(e: AnActionEvent): Boolean =
                com.github.copilot.intellij.services.CopilotSettings.getUseNewChatPane()

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                com.github.copilot.intellij.services.CopilotSettings.setUseNewChatPane(state)
                javax.swing.SwingUtilities.invokeLater { content.rebuildChatPanel() }
            }
        })
        toolWindow.setAdditionalGearActions(gearGroup)
    }
}
