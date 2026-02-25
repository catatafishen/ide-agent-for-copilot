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
            },
            object : AnAction("Debug", "Open debug log", AllIcons.Actions.StartDebugger) {
                override fun actionPerformed(e: AnActionEvent) {
                    content.openDebug()
                }
            }
        ))
    }
}
