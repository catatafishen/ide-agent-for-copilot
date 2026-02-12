package com.github.copilot.intellij.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating the Agentic Copilot tool window.
 * This is registered in plugin.xml and invoked when the tool window is first opened.
 */
public class AgenticCopilotToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Create the Kotlin UI content
        AgenticCopilotToolWindowContent content = new AgenticCopilotToolWindowContent(project);
        
        // Wrap in IntelliJ Content and add to tool window
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content toolWindowContent = contentFactory.createContent(
            content.getComponent(),
            "",
            false
        );
        toolWindow.getContentManager().addContent(toolWindowContent);
    }
}
