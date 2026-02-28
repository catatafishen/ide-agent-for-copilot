package com.github.copilot.intellij.ui

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
    }
}
