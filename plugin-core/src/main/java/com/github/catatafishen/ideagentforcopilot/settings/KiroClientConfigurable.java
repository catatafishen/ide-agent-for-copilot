package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class KiroClientConfigurable implements Configurable {

    @SuppressWarnings("unused")
    public KiroClientConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Kiro";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private JBTextField instructionsFileField;
    private JPanel panel;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the kiro-cli binary. Leave empty to find it on PATH.");

        instructionsFileField = new JBTextField();
        instructionsFileField.getEmptyText().setText("E.g. AGENTS.md");
        instructionsFileField.setToolTipText(
            "File relative to project root. Plugin instructions are prepended here on each session start.");

        HyperlinkLabel docsLink = new HyperlinkLabel("Kiro CLI documentation at kiro.dev/docs/cli/acp");
        docsLink.setHyperlinkTarget("https://kiro.dev/docs/cli/acp/");

        JBLabel authNote = new JBLabel(
            "<html>Ensure <code>kiro-cli</code> is installed and available on your PATH.</html>");
        authNote.setForeground(UIUtil.getContextHelpForeground());
        authNote.setFont(JBUI.Fonts.smallFont());

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusLabel)
            .addComponent(authNote, 2)
            .addComponent(docsLink, 2)
            .addSeparator(8)
            .addLabeledComponent("Kiro binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Instructions file:", instructionsFileField)
            .addTooltip("Plugin instructions are prepended here on session start (relative to project root).")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    @Override
    public boolean isModified() {
        if (binaryPathField == null) return false;
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        if (p == null) return false;
        return !binaryPathField.getText().trim().equals(nullToEmpty(p.getCustomBinaryPath()))
            || !instructionsFileField.getText().trim().equals(nullToEmpty(p.getPrependInstructionsTo()));
    }

    @Override
    public void apply() {
        if (binaryPathField == null) return;
        AgentProfileManager mgr = AgentProfileManager.getInstance();
        AgentProfile p = mgr.getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        if (p == null) return;
        p.setCustomBinaryPath(binaryPathField.getText().trim());
        p.setPrependInstructionsTo(instructionsFileField.getText().trim());
        mgr.updateProfile(p);
    }

    @Override
    public void reset() {
        if (binaryPathField == null) return;
        refreshStatusAsync();
        AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        if (p == null) return;
        binaryPathField.setText(nullToEmpty(p.getCustomBinaryPath()));
        instructionsFileField.setText(nullToEmpty(p.getPrependInstructionsTo()));
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        instructionsFileField = null;
        panel = null;
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AgentProfile p = AgentProfileManager.getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
            String binary = p != null ? p.getBinaryName() : "kiro-cli";
            String[] alternates = p != null ? p.getAlternateNames().toArray(new String[0]) : new String[]{};
            String version = BinaryDetector.detectBinaryVersion(binary, alternates);
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ Kiro CLI found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("Kiro CLI not found on PATH — install from kiro.dev");
                    statusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    @NotNull
    private static String nullToEmpty(@Nullable String s) {
        return s != null ? s : "";
    }
}
