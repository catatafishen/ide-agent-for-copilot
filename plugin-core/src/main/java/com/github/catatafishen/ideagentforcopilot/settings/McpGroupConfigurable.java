package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Group node: Settings → Tools → IDE Agent for Copilot → MCP.
 * Contains child pages for Server and Tools.
 */
public final class McpGroupConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.mcp";

    @SuppressWarnings("unused") // injected by the platform via projectConfigurable
    private final Project project;

    public McpGroupConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MCP";
    }

    @Override
    public @Nullable JComponent createComponent() {
        return FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>"
                    + "<b>Model Context Protocol (MCP)</b><br><br>"
                    + "Configure the MCP server that exposes IDE tools to the agent.<br><br>"
                    + "Use the sub-pages to manage:<br>"
                    + "• <b>Server</b> — port, transport mode, auto-start<br>"
                    + "• <b>Tools</b> — enable or disable individual tools"
                    + "</html>"))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // group page — no settings
    }
}
