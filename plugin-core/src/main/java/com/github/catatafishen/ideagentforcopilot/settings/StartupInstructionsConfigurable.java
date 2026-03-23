package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings UI for customizing agent startup instructions.
 */
public final class StartupInstructionsConfigurable implements Configurable {

    private JPanel rootPanel;
    private JBTextArea instructionsArea;
    private JCheckBox useCustomCheckBox;

    private final StartupInstructionsSettings settings = StartupInstructionsSettings.getInstance();

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Agent Instructions";
    }

    @Override
    public @Nullable JComponent createComponent() {
        useCustomCheckBox = new JCheckBox("Use custom startup instructions");
        useCustomCheckBox.addActionListener(e -> updateUIState());

        JLabel infoLabel = new JLabel(
            "<html><i>Sent to all agents at the start of each session. "
                + "Changes take effect for new sessions only.</i></html>");
        infoLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());

        instructionsArea = new JBTextArea();
        instructionsArea.setFont(JBUI.Fonts.create("monospace", 12));
        instructionsArea.setRows(20);
        instructionsArea.setWrapStyleWord(true);
        instructionsArea.setLineWrap(true);

        JBScrollPane scrollPane = new JBScrollPane(instructionsArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JButton resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> resetToDefault());

        JPanel buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4));
        buttonPanel.add(resetButton);

        rootPanel = new JPanel(new java.awt.BorderLayout());
        rootPanel.setBorder(JBUI.Borders.empty(8));

        JPanel headerPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        headerPanel.add(useCustomCheckBox, java.awt.BorderLayout.NORTH);
        headerPanel.add(infoLabel, java.awt.BorderLayout.CENTER);
        headerPanel.setBorder(JBUI.Borders.emptyBottom(8));

        rootPanel.add(headerPanel, java.awt.BorderLayout.NORTH);
        rootPanel.add(scrollPane, java.awt.BorderLayout.CENTER);
        rootPanel.add(buttonPanel, java.awt.BorderLayout.SOUTH);

        return rootPanel;
    }

    private void updateUIState() {
        boolean useCustom = useCustomCheckBox.isSelected();
        instructionsArea.setEnabled(useCustom);

        if (!useCustom) {
            // Show default template when switching to default
            instructionsArea.setText(settings.getDefaultTemplate());
            instructionsArea.setEditable(false);
            instructionsArea.setBackground(UIManager.getColor("TextField.disabledBackground"));
        } else {
            instructionsArea.setEditable(true);
            instructionsArea.setBackground(UIManager.getColor("TextField.background"));
        }
    }

    private void resetToDefault() {
        instructionsArea.setText(settings.getDefaultTemplate());
        useCustomCheckBox.setSelected(false);
        updateUIState();
    }

    @Override
    public boolean isModified() {
        if (useCustomCheckBox.isSelected() != settings.isUsingCustomInstructions()) {
            return true;
        }

        if (useCustomCheckBox.isSelected()) {
            String current = instructionsArea.getText().trim();
            String stored = settings.getCustomInstructions();
            stored = stored != null ? stored.trim() : "";
            return !current.equals(stored);
        }

        return false;
    }

    @Override
    public void apply() {
        if (useCustomCheckBox.isSelected()) {
            String text = instructionsArea.getText();
            settings.setCustomInstructions(text.trim().isEmpty() ? null : text);
        } else {
            settings.setCustomInstructions(null);
        }
    }

    @Override
    public void reset() {
        boolean usingCustom = settings.isUsingCustomInstructions();
        useCustomCheckBox.setSelected(usingCustom);

        if (usingCustom) {
            instructionsArea.setText(settings.getCustomInstructions());
        } else {
            instructionsArea.setText(settings.getDefaultTemplate());
        }

        updateUIState();
    }

    @Override
    public void disposeUIResources() {
        rootPanel = null;
        instructionsArea = null;
        useCustomCheckBox = null;
    }
}
