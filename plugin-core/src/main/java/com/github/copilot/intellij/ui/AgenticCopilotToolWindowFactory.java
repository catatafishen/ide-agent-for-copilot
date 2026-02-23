package com.github.copilot.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory for creating the Agentic Copilot tool window.
 * This is registered in plugin.xml and invoked when the tool window is first opened.
 */
public class AgenticCopilotToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AgenticCopilotToolWindowContent content = new AgenticCopilotToolWindowContent(project);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content toolWindowContent = contentFactory.createContent(
                content.getComponent(),
                "",
                false
        );
        toolWindow.getContentManager().addContent(toolWindowContent);

        // Add "New Chat" action to tool window title bar
        toolWindow.setTitleActions(List.of(
                new AnAction("New Chat", "Start a fresh conversation", AllIcons.Actions.Restart) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        content.resetSession();
                    }
                }
        ));
    }
}
