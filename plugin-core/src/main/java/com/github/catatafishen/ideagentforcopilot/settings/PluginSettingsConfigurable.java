package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class PluginSettingsConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.settings";
    public static final String DISPLAY_NAME = "AgentBridge";

    private JComboBox<String> triggerCharCombo;

    @SuppressWarnings("unused")
    public PluginSettingsConfigurable(@NotNull Project ignoredProject) {
    }

    /**
     * Opens this settings page programmatically.
     */
    public static void open(@NotNull Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable.class);
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public @NotNull JComponent createComponent() {
        triggerCharCombo = new JComboBox<>(new String[]{"# (VS Code style)", "@ (AI Assistant style)", "Disabled"});

        String version = com.github.catatafishen.ideagentforcopilot.BuildInfo.getVersion();
        String hash = com.github.catatafishen.ideagentforcopilot.BuildInfo.getGitHash();

        JBLabel descLabel = new JBLabel(
            "<html>"
                + "AgentBridge connects IntelliJ IDE with AI coding agents via the "
                + "<b>Agent Coding Protocol (ACP)</b>. Agents gain live access to code intelligence, "
                + "refactoring, search, file editing, and build tools through the MCP server built "
                + "into the IDE.<br><br>"
                + "Supported clients: <b>GitHub Copilot</b>, <b>OpenCode</b>, <b>Claude Code</b>, "
                + "<b>Claude CLI</b>, <b>Junie</b>, <b>Kiro</b>."
                + "</html>");
        descLabel.setForeground(UIUtil.getContextHelpForeground());

        JBLabel versionLabel = new JBLabel("Version " + version + "  ·  " + hash);
        versionLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        versionLabel.setFont(JBUI.Fonts.smallFont());

        JPanel panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("File search trigger:", triggerCharCombo)
            .addTooltip("Character that opens the file search popup in the chat input.")
            .addSeparator(12)
            .addComponent(descLabel)
            .addVerticalGap(8)
            .addComponent(versionLabel)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));

        reset();
        return panel;
    }

    private String selectedTriggerChar() {
        int idx = triggerCharCombo == null ? 0 : triggerCharCombo.getSelectedIndex();
        return switch (idx) {
            case 1 -> "@";
            case 2 -> "";
            default -> "#";
        };
    }

    private void selectTriggerChar(String value) {
        if (triggerCharCombo == null) return;
        triggerCharCombo.setSelectedIndex(switch (value) {
            case "@" -> 1;
            case "" -> 2;
            default -> 0;
        });
    }

    @Override
    public boolean isModified() {
        return triggerCharCombo != null
            && !selectedTriggerChar().equals(ActiveAgentManager.getAttachTriggerChar());
    }

    @Override
    public void apply() {
        ActiveAgentManager.setAttachTriggerChar(selectedTriggerChar());
    }

    @Override
    public void reset() {
        selectTriggerChar(ActiveAgentManager.getAttachTriggerChar());
    }
}
