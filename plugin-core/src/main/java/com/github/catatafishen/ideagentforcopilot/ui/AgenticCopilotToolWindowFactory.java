package com.github.catatafishen.ideagentforcopilot.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class AgenticCopilotToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AgenticCopilotToolWindowContent content = new AgenticCopilotToolWindowContent(project, toolWindow);
        Content toolWindowContent = ContentFactory.getInstance().createContent(
            content.getComponent(), "", false
        );
        toolWindow.getContentManager().addContent(toolWindowContent);
    }
}
