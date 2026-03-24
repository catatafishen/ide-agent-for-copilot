package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ChatWebServer;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Settings page for the Chat Web Server.
 * Located under Settings → Tools → AgentBridge → Web Access.
 */
public final class ChatWebServerConfigurable implements Configurable {

    private final Project project;
    private JBCheckBox enabledCheckbox;
    private JSpinner portSpinner;
    private JButton startStopButton;
    private JLabel httpsUrlLabel;
    private JLabel httpUrlLabel;
    private QrCodePanel httpsQrPanel;
    private QrCodePanel certQrPanel;
    private JPanel mainPanel;

    public ChatWebServerConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Web Access";
    }

    @Override
    public @NotNull JComponent createComponent() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);

        enabledCheckbox = new JBCheckBox(
            "Start web server automatically when project opens",
            settings.isEnabled());

        portSpinner = new JSpinner(new SpinnerNumberModel(settings.getPort(), 1024, 65535, 1));

        startStopButton = new JButton(getStartStopLabel());
        startStopButton.addActionListener(e -> toggleServer());

        httpsUrlLabel = new JBLabel("");
        httpsUrlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        httpUrlLabel = new JBLabel("");
        httpUrlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        httpsQrPanel = new QrCodePanel();
        certQrPanel = new QrCodePanel();
        updateUrlLabels();

        JButton copyHttpsUrlButton = new JButton("Copy HTTPS");
        copyHttpsUrlButton.addActionListener(e -> {
            String url = getHttpsServerUrl();
            if (!url.isEmpty()) {
                copyToClipboard(url, copyHttpsUrlButton);
            }
        });

        JButton copyHttpUrlButton = new JButton("Copy HTTP");
        copyHttpUrlButton.addActionListener(e -> {
            String url = getHttpServerUrl();
            if (!url.isEmpty()) {
                copyToClipboard(url, copyHttpUrlButton);
            }
        });

        JPanel httpsUrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        httpsUrlRow.add(new JBLabel("HTTPS (PWA):"));
        httpsUrlRow.add(httpsUrlLabel);
        httpsUrlRow.add(copyHttpsUrlButton);

        JPanel httpUrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        httpUrlRow.add(new JBLabel("HTTP (Legacy):"));
        httpUrlRow.add(httpUrlLabel);
        httpUrlRow.add(copyHttpUrlButton);

        JPanel httpsQrRow = buildQrRow(httpsQrPanel, "Open on phone:");
        JPanel certQrRow = buildQrRow(certQrPanel, "Install CA cert:");

        JPanel qrSection = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        qrSection.add(httpsQrRow);
        qrSection.add(certQrRow);

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel("<html>Serve the chat panel as a local web app accessible from "
                + "any device on the same network (phone, tablet, etc.).<br>"
                + "Supports prompt sending, nudging, quick replies, and PWA push notifications.</html>"))
            .addSeparator()
            .addComponent(enabledCheckbox)
            .addLabeledComponent("Base Port:", portSpinner)
            .addComponent(new JBLabel("<html><i style='font-size:smaller;color:gray'>HTTPS uses Base Port, HTTP uses Base Port + 1</i></html>"))
            .addSeparator()
            .addComponent(startStopButton)
            .addComponent(httpsUrlRow)
            .addComponent(httpUrlRow)
            .addComponent(qrSection)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    private static JPanel buildQrRow(QrCodePanel qr, String label) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        JBLabel lbl = new JBLabel(label);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        qr.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(lbl);
        col.add(Box.createVerticalStrut(4));
        col.add(qr);
        return col;
    }

    @Override
    public boolean isModified() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        if (enabledCheckbox.isSelected() != settings.isEnabled()) return true;
        return (Integer) portSpinner.getValue() != settings.getPort();
    }

    @Override
    public void apply() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        settings.setEnabled(enabledCheckbox.isSelected());
        settings.setPort((Integer) portSpinner.getValue());
    }

    @Override
    public void reset() {
        ChatWebServerSettings settings = ChatWebServerSettings.getInstance(project);
        enabledCheckbox.setSelected(settings.isEnabled());
        portSpinner.setValue(settings.getPort());
        updateUrlLabels();
        if (startStopButton != null) startStopButton.setText(getStartStopLabel());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        enabledCheckbox = null;
        portSpinner = null;
        startStopButton = null;
        httpsUrlLabel = null;
        httpUrlLabel = null;
        httpsQrPanel = null;
        certQrPanel = null;
    }

    private void toggleServer() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws.isRunning()) {
            ws.stop();
            refresh();
        } else {
            apply(); // save port before starting
            startStopButton.setEnabled(false);
            startStopButton.setText("Starting…");
            new Thread(() -> {
                try {
                    ws.start();
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(mainPanel,
                            "Failed to start web server: " + e.getMessage(),
                            "Chat Web Server Error", JOptionPane.ERROR_MESSAGE);
                        refresh();
                    });
                    return;
                }
                SwingUtilities.invokeLater(this::refresh);
            }, "ChatWebServer-start").start();
        }
    }

    private void refresh() {
        if (startStopButton == null) return;
        startStopButton.setEnabled(true);
        startStopButton.setText(getStartStopLabel());
        updateUrlLabels();
        if (mainPanel != null) {
            mainPanel.revalidate();
            mainPanel.repaint();
        }
    }

    private String getStartStopLabel() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        return ws != null && ws.isRunning() ? "Stop Web Server" : "Start Web Server";
    }

    private void updateUrlLabels() {
        if (httpsUrlLabel == null || httpUrlLabel == null) return;
        String httpsUrl = getHttpsServerUrl();
        String httpUrl = getHttpServerUrl();

        if (httpsUrl.isEmpty()) {
            httpsUrlLabel.setText("<html><i style='color:gray'>Not running</i></html>");
            httpUrlLabel.setText("<html><i style='color:gray'>Not running</i></html>");
            if (httpsQrPanel != null) httpsQrPanel.setUrl(null);
            if (certQrPanel != null) certQrPanel.setUrl(null);
        } else {
            httpsUrlLabel.setText("<html><a href='" + httpsUrl + "'>" + httpsUrl + "</a></html>");
            httpUrlLabel.setText("<html><a href='" + httpUrl + "'>" + httpUrl + "</a></html>");
            if (httpsQrPanel != null) httpsQrPanel.setUrl(httpsUrl);
            if (certQrPanel != null) certQrPanel.setUrl(getCertUrl());
        }
    }

    private String getHttpsServerUrl() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws == null || !ws.isRunning()) return "";
        return buildUrl("https", ws.getPort());
    }

    private String getHttpServerUrl() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws == null || !ws.isRunning()) return "";
        return buildUrl("http", ws.getHttpPort());
    }

    private String getCertUrl() {
        ChatWebServer ws = ChatWebServer.getInstance(project);
        if (ws == null || !ws.isRunning()) return "";
        // Serve cert over HTTP so the device can download it before trusting the HTTPS cert.
        return buildUrl("http", ws.getHttpPort()) + "/cert.crt";
    }

    private String buildUrl(String protocol, int port) {
        String lanIp = ChatWebServer.getLanIp();
        String host = lanIp != null ? lanIp : "localhost";
        return protocol + "://" + host + ":" + port;
    }

    private void copyToClipboard(String text, JButton feedbackButton) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
        String orig = feedbackButton.getText();
        feedbackButton.setText("Copied!");
        Timer t = new Timer(2000, ev -> feedbackButton.setText(orig));
        t.setRepeats(false);
        t.start();
    }
}
