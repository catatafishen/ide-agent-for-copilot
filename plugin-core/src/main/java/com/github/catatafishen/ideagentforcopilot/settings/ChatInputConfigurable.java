package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Settings page at <b>Settings → Tools → AgentBridge → Chat Input</b>.
 * Consolidates shortcut-hint visibility, smart-paste behaviour, and
 * the file-search trigger character.
 */
public final class ChatInputConfigurable implements Configurable {

    private JBCheckBox showHintsCheckBox;
    private JBCheckBox smartPasteCheckBox;
    private JSpinner smartPasteMinLinesSpinner;
    private JSpinner smartPasteMinCharsSpinner;
    private JComboBox<String> triggerCharCombo;

    @SuppressWarnings("unused")
    public ChatInputConfigurable(@NotNull Project ignoredProject) {
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Chat Input";
    }

    @Override
    public @NotNull JComponent createComponent() {
        showHintsCheckBox = new JBCheckBox("Show keyboard shortcut hints in prompt placeholder");
        smartPasteCheckBox = new JBCheckBox("Enable smart paste");
        smartPasteMinLinesSpinner = new JSpinner(new SpinnerNumberModel(
            ChatInputSettings.DEFAULT_SMART_PASTE_MIN_LINES, 1, 100, 1));
        smartPasteMinCharsSpinner = new JSpinner(new SpinnerNumberModel(
            ChatInputSettings.DEFAULT_SMART_PASTE_MIN_CHARS, 50, 10_000, 50));
        triggerCharCombo = new JComboBox<>(new String[]{
            "# (VS Code style)", "@ (AI Assistant style)", "Disabled"
        });

        JBLabel descLabel = new JBLabel(
            "<html>Configure the chat input area: keyboard shortcut hints, "
                + "smart paste behaviour, and file search trigger character.</html>");
        descLabel.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(descLabel)
            .addVerticalGap(8)
            .addSeparator(8)
            .addComponent(showHintsCheckBox)
            .addTooltip("Display Enter/Shift+Enter/Ctrl+Enter hints in the prompt placeholder text.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addComponent(smartPasteCheckBox)
            .addTooltip("Intercept large clipboard pastes to create scratch files or inline file references.")
            .addLabeledComponent("Min lines to trigger:", smartPasteMinLinesSpinner)
            .addTooltip("Clipboard content with more lines than this triggers Smart Paste.")
            .addLabeledComponent("Min characters to trigger:", smartPasteMinCharsSpinner)
            .addTooltip("Clipboard content with more characters than this triggers Smart Paste.")
            .addVerticalGap(4)
            .addSeparator(8)
            .addLabeledComponent("File search trigger:", triggerCharCombo)
            .addTooltip("Character that opens the file search popup in the chat input.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));

        smartPasteCheckBox.addActionListener(e -> updateSmartPasteSpinnerState());

        reset();
        return panel;
    }

    private void updateSmartPasteSpinnerState() {
        boolean enabled = smartPasteCheckBox.isSelected();
        smartPasteMinLinesSpinner.setEnabled(enabled);
        smartPasteMinCharsSpinner.setEnabled(enabled);
    }

    @Override
    public boolean isModified() {
        if (showHintsCheckBox == null) return false;
        ChatInputSettings s = ChatInputSettings.getInstance();
        if (showHintsCheckBox.isSelected() != s.isShowShortcutHints()) return true;
        if (smartPasteCheckBox.isSelected() != s.isSmartPasteEnabled()) return true;
        if ((int) smartPasteMinLinesSpinner.getValue() != s.getSmartPasteMinLines()) return true;
        if ((int) smartPasteMinCharsSpinner.getValue() != s.getSmartPasteMinChars()) return true;
        return !selectedTriggerChar().equals(s.getFileSearchTrigger());
    }

    @Override
    public void apply() {
        ChatInputSettings s = ChatInputSettings.getInstance();
        s.setShowShortcutHints(showHintsCheckBox.isSelected());
        s.setSmartPasteEnabled(smartPasteCheckBox.isSelected());
        s.setSmartPasteMinLines((int) smartPasteMinLinesSpinner.getValue());
        s.setSmartPasteMinChars((int) smartPasteMinCharsSpinner.getValue());
        s.setFileSearchTrigger(selectedTriggerChar());
    }

    @Override
    public void reset() {
        ChatInputSettings s = ChatInputSettings.getInstance();
        showHintsCheckBox.setSelected(s.isShowShortcutHints());
        smartPasteCheckBox.setSelected(s.isSmartPasteEnabled());
        smartPasteMinLinesSpinner.setValue(s.getSmartPasteMinLines());
        smartPasteMinCharsSpinner.setValue(s.getSmartPasteMinChars());
        selectTriggerChar(s.getFileSearchTrigger());
        updateSmartPasteSpinnerState();
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
}
