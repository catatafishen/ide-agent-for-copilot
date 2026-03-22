package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

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
        rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBorder(JBUI.Borders.empty(12));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        
        useCustomCheckBox = new JCheckBox("Use custom startup instructions (uncheck to use default template)");
        useCustomCheckBox.addActionListener(e -> updateUIState());
        headerPanel.add(useCustomCheckBox, BorderLayout.NORTH);
        
        // Info label
        JLabel infoLabel = new JLabel(
            "<html><i>These instructions are sent to all agents at session start. " +
            "Changes apply to new sessions only.</i></html>");
        infoLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        infoLabel.setBorder(JBUI.Borders.emptyTop(8));
        headerPanel.add(infoLabel, BorderLayout.SOUTH);
        
        rootPanel.add(headerPanel, BorderLayout.NORTH);

        // Text area
        instructionsArea = new JBTextArea();
        instructionsArea.setFont(JBUI.Fonts.create("monospace", 12));
        instructionsArea.setRows(20);
        instructionsArea.setWrapStyleWord(true);
        instructionsArea.setLineWrap(true);
        
        JBScrollPane scrollPane = new JBScrollPane(instructionsArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(JBUI.Borders.emptyTop(12));
        
        rootPanel.add(scrollPane, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
        
        JButton resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> resetToDefault());
        buttonPanel.add(resetButton);
        
        JButton previewButton = new JButton("Preview Default");
        previewButton.addActionListener(e -> previewDefault());
        buttonPanel.add(previewButton);
        
        rootPanel.add(buttonPanel, BorderLayout.SOUTH);

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

    private void previewDefault() {
        String current = instructionsArea.getText();
        String defaultText = settings.getDefaultTemplate();
        
        instructionsArea.setText(defaultText);
        instructionsArea.setCaretPosition(0);
        
        JOptionPane.showMessageDialog(rootPanel, 
            "Default template shown in editor. Your changes are preserved and will be restored when you close this dialog.",
            "Preview Default Template", 
            JOptionPane.INFORMATION_MESSAGE);
        
        // Restore previous content
        instructionsArea.setText(current);
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