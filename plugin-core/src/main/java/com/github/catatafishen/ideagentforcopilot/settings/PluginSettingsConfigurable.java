package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class PluginSettingsConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.settings";
    public static final String DISPLAY_NAME = "AgentBridge";

    private final Project project;

    private ScratchTypesConfigurable scratchTypesConfigurable;
    private ProjectFilesConfigurable projectFilesConfigurable;
    private ChatHistoryConfigurable chatHistoryConfigurable;
    private StartupInstructionsConfigurable startupInstructionsConfigurable;

    public PluginSettingsConfigurable(@NotNull Project project) {
        this.project = project;
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
        scratchTypesConfigurable = new ScratchTypesConfigurable();
        projectFilesConfigurable = new ProjectFilesConfigurable();
        chatHistoryConfigurable = new ChatHistoryConfigurable(project);
        startupInstructionsConfigurable = new StartupInstructionsConfigurable();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("About", buildAboutPanel());
        tabs.addTab("Scratch File Types", scratchTypesConfigurable.createComponent());
        tabs.addTab("Project Files", projectFilesConfigurable.createComponent());
        tabs.addTab("Chat History", chatHistoryConfigurable.createComponent());
        tabs.addTab("Agent Instructions", startupInstructionsConfigurable.createComponent());

        reset();

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(tabs, BorderLayout.NORTH);
        return wrapper;
    }

    private @NotNull JPanel buildAboutPanel() {
        String version = com.github.catatafishen.ideagentforcopilot.BuildInfo.getVersion();
        String hash = com.github.catatafishen.ideagentforcopilot.BuildInfo.getGitHash();

        JBLabel descLabel = new JBLabel(
            "<html>"
                + "<b>" + DISPLAY_NAME + "</b> bridges your IntelliJ IDE with AI coding agents.<br><br>"
                + "Agents connect via the <b>Agent Coding Protocol (ACP)</b> and gain access to<br>"
                + "live code intelligence, refactoring, search, file editing, and build tools<br>"
                + "through the <b>MCP server</b> running inside the IDE.<br><br>"
                + "Supported clients: <b>GitHub Copilot</b>, <b>OpenCode</b>, <b>Claude Code</b>, <b>Claude CLI</b>, <b>Junie</b>."
                + "</html>");
        descLabel.setBorder(JBUI.Borders.empty(4, 0, 12, 0));

        JBLabel versionLabel = new JBLabel("Version " + version + "  ·  " + hash);
        versionLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        versionLabel.setFont(JBUI.Fonts.smallFont());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        panel.add(descLabel, gbc);

        gbc.gridy = 1;
        panel.add(versionLabel, gbc);

        // Add a filler at the bottom to push everything to the top
        gbc.gridy = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    @Override
    public boolean isModified() {
        return (scratchTypesConfigurable != null && scratchTypesConfigurable.isModified())
            || (projectFilesConfigurable != null && projectFilesConfigurable.isModified())
            || (chatHistoryConfigurable != null && chatHistoryConfigurable.isModified())
            || (startupInstructionsConfigurable != null && startupInstructionsConfigurable.isModified());
    }

    @Override
    public void apply() {
        if (scratchTypesConfigurable != null) scratchTypesConfigurable.apply();
        if (projectFilesConfigurable != null) projectFilesConfigurable.apply();
        if (chatHistoryConfigurable != null) chatHistoryConfigurable.apply();
        if (startupInstructionsConfigurable != null) startupInstructionsConfigurable.apply();
    }

    @Override
    public void reset() {
        if (scratchTypesConfigurable != null) scratchTypesConfigurable.reset();
        if (projectFilesConfigurable != null) projectFilesConfigurable.reset();
        if (chatHistoryConfigurable != null) chatHistoryConfigurable.reset();
        if (startupInstructionsConfigurable != null) startupInstructionsConfigurable.reset();
    }

    @Override
    public void disposeUIResources() {
        if (scratchTypesConfigurable != null) {
            scratchTypesConfigurable.disposeUIResources();
            scratchTypesConfigurable = null;
        }
        if (projectFilesConfigurable != null) {
            projectFilesConfigurable.disposeUIResources();
            projectFilesConfigurable = null;
        }
        if (chatHistoryConfigurable != null) {
            chatHistoryConfigurable.disposeUIResources();
            chatHistoryConfigurable = null;
        }
        if (startupInstructionsConfigurable != null) {
            startupInstructionsConfigurable.disposeUIResources();
            startupInstructionsConfigurable = null;
        }
    }
}
