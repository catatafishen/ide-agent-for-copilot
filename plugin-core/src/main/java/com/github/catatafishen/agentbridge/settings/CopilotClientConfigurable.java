package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.acp.client.AcpClient;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.github.catatafishen.agentbridge.ui.ThemeColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public final class CopilotClientConfigurable implements Configurable {

    private static final String AGENT_ID = "copilot";

    private final Project project;

    public CopilotClientConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "GitHub Copilot";
    }

    private JBLabel statusLabel;
    private JBTextField binaryPathField;
    private @Nullable ThemeColorComboBox bubbleColorCombo;
    private BillingConfigurable billingConfigurable;
    private @Nullable JBCheckBox remoteSessionCheckbox;

    @Override
    public @NotNull JComponent createComponent() {
        statusLabel = new JBLabel();

        JButton recheckButton = new JButton("Recheck");
        recheckButton.addActionListener(e -> refreshStatusAsync());
        recheckButton.setToolTipText("Check if the binary is available");

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.add(statusLabel);
        statusRow.add(recheckButton);

        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (leave empty)");
        binaryPathField.setToolTipText("Absolute path to the copilot binary. Leave empty to find it on PATH.");

        bubbleColorCombo = new ThemeColorComboBox();

        HyperlinkLabel installLink = new HyperlinkLabel("Install from github.com/github/copilot-cli");
        installLink.setHyperlinkTarget("https://github.com/github/copilot-cli#installation");

        JBLabel installNote = new JBLabel(
            "<html>Install with <code>npm install -g @github/copilot-cli</code>. Ensure it's available on PATH.</html>");
        installNote.setForeground(UIUtil.getContextHelpForeground());
        installNote.setFont(JBUI.Fonts.smallFont());

        remoteSessionCheckbox = new JBCheckBox("Enable remote control mode (--remote)");
        remoteSessionCheckbox.setSelected(ActiveAgentManager.getInstance(project).isRemoteMode());
        remoteSessionCheckbox.setToolTipText(
            "Launches Copilot with --remote so a remote session can be established.");

        JBLabel remoteWarning = new JBLabel(
            "<html><b>⚠ Not currently usable in ACP mode.</b> Copilot in ACP mode (--acp --stdio) does not"
                + " expose the session URL needed to connect remotely. Remote sessions only work in Copilot's"
                + " interactive terminal mode. Enable this once Copilot supports remote URL delivery in ACP"
                + " mode, or if you can find the session directly from github.com/copilot.</html>");
        remoteWarning.setForeground(Color.RED);
        remoteWarning.setFont(JBUI.Fonts.smallFont());

        JPanel configPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Status:", statusRow)
            .addComponent(installNote, 2)
            .addComponent(installLink, 2)
            .addSeparator(8)
            .addLabeledComponent("Copilot binary:", binaryPathField)
            .addTooltip("Leave empty to auto-detect on PATH.")
            .addLabeledComponent("Bubble color:", bubbleColorCombo)
            .addTooltip("Choose a theme-aware accent color for message bubbles when using GitHub Copilot.")
            .addSeparator(8)
            .addComponent(remoteSessionCheckbox)
            .addComponentToRightColumn(remoteWarning, 4)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        configPanel.setBorder(JBUI.Borders.empty(8));

        JBScrollPane configScroll = new JBScrollPane(configPanel);
        configScroll.setBorder(null);

        billingConfigurable = new BillingConfigurable();
        JComponent billingPanel = billingConfigurable.createComponent();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configuration", configScroll);
        tabs.addTab("Billing Data", billingPanel);

        return tabs;
    }

    @Override
    public boolean isModified() {
        String stored = nullToEmpty(AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID));
        boolean binaryChanged = binaryPathField != null
            && !binaryPathField.getText().trim().equals(stored);
        if (binaryChanged) return true;
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            String key = tc != null ? tc.name() : null;
            if (!java.util.Objects.equals(key, AcpClient.loadAgentBubbleColorKey(AGENT_ID))) return true;
        }
        if (remoteSessionCheckbox != null
            && remoteSessionCheckbox.isSelected() != ActiveAgentManager.getInstance(project).isRemoteMode()) {
            return true;
        }
        return billingConfigurable != null && billingConfigurable.isModified();
    }

    @Override
    public void apply() {
        if (binaryPathField != null) {
            AgentProfileManager.getInstance().saveBinaryPath(AGENT_ID, binaryPathField.getText().trim());
        }
        if (bubbleColorCombo != null) {
            ThemeColor tc = bubbleColorCombo.getSelectedThemeColor();
            AcpClient.saveAgentBubbleColorKey(AGENT_ID, tc != null ? tc.name() : null);
        }
        if (remoteSessionCheckbox != null) {
            ActiveAgentManager.getInstance(project).setRemoteMode(remoteSessionCheckbox.isSelected());
        }
        if (billingConfigurable != null) billingConfigurable.apply();
    }

    @Override
    public void reset() {
        if (binaryPathField != null) {
            refreshStatusAsync();
            binaryPathField.setText(nullToEmpty(AgentProfileManager.getInstance().loadBinaryPath(AGENT_ID)));
        }
        if (bubbleColorCombo != null) {
            bubbleColorCombo.setSelectedThemeColor(ThemeColor.fromKey(AcpClient.loadAgentBubbleColorKey(AGENT_ID)));
        }
        if (remoteSessionCheckbox != null) {
            remoteSessionCheckbox.setSelected(ActiveAgentManager.getInstance(project).isRemoteMode());
        }
        if (billingConfigurable != null) billingConfigurable.reset();
    }

    @Override
    public void disposeUIResources() {
        statusLabel = null;
        binaryPathField = null;
        bubbleColorCombo = null;
        remoteSessionCheckbox = null;
        if (billingConfigurable != null) {
            billingConfigurable.disposeUIResources();
            billingConfigurable = null;
        }
    }

    private void refreshStatusAsync() {
        if (statusLabel == null) return;
        statusLabel.setText("Checking...");
        statusLabel.setForeground(UIUtil.getLabelForeground());

        String liveCustomPath = binaryPathField != null ? binaryPathField.getText().trim() : null;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            AgentBinaryResolver resolver = new AcpClientBinaryResolver(AGENT_ID, AGENT_ID, "copilot-cli");

            if (liveCustomPath != null && !liveCustomPath.isEmpty()) {
                File file = new File(liveCustomPath);
                if (!file.exists()) {
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel == null) return;
                        statusLabel.setText("✗ File not found: " + liveCustomPath);
                        statusLabel.setForeground(Color.RED);
                    });
                    return;
                }
                if (!file.canExecute()) {
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel == null) return;
                        statusLabel.setText("✗ File not executable: " + liveCustomPath);
                        statusLabel.setForeground(Color.RED);
                    });
                    return;
                }
                resolver = resolver.withCustomPath(liveCustomPath);
            }

            String version = resolver.detectVersion();
            SwingUtilities.invokeLater(() -> {
                if (statusLabel == null) return;
                if (version != null) {
                    statusLabel.setText("✓ GitHub Copilot found — " + version);
                    statusLabel.setForeground(new Color(0, 128, 0));
                } else {
                    statusLabel.setText("GitHub Copilot not found on PATH — install from github.com/github/copilot-cli");
                    statusLabel.setForeground(Color.RED);
                }
            });
        });
    }

    @NotNull
    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
