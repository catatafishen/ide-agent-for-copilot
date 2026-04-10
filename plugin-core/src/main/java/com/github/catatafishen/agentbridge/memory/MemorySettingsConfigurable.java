package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.mining.BackfillMiner;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings UI panel for the Semantic Memory feature.
 * Displayed under AgentBridge > Memory in the IDE Settings.
 */
public final class MemorySettingsConfigurable implements Configurable {

    private final Project project;

    private JCheckBox enabledCheckBox;
    private JCheckBox autoMineTurnCheckBox;
    private JCheckBox autoMineArchiveCheckBox;
    private JSpinner minChunkLengthSpinner;
    private JSpinner maxDrawersPerTurnSpinner;
    private JTextField palaceWingField;
    private JButton backfillButton;
    private JLabel backfillStatusLabel;

    public MemorySettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Memory";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        // ── Description ──
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        JLabel desc = new JLabel(
            "<html>Semantic memory powered by concepts from " +
                "<a href=\"https://github.com/milla-jovovich/mempalace\">MemPalace</a>. " +
                "Stores decisions, preferences, and milestones from conversations " +
                "for cross-session recall.</html>");
        panel.add(desc, gbc);

        // ── Enabled ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        enabledCheckBox = new JCheckBox("Enable semantic memory (stores memories locally in .agent-work/memory/)");
        panel.add(enabledCheckBox, gbc);

        // ── Auto-mine on turn complete ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        autoMineTurnCheckBox = new JCheckBox("Automatically mine memories after each agent turn");
        panel.add(autoMineTurnCheckBox, gbc);

        // ── Auto-mine on session archive ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        autoMineArchiveCheckBox = new JCheckBox("Mine remaining entries when a session is archived");
        panel.add(autoMineArchiveCheckBox, gbc);

        // ── Min chunk length ──
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        panel.add(new JLabel("Minimum chunk length (chars):"), gbc);
        gbc.gridx = 1;
        minChunkLengthSpinner = new JSpinner(new SpinnerNumberModel(200, 50, 2000, 50));
        panel.add(minChunkLengthSpinner, gbc);

        // ── Max drawers per turn ──
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Max drawers per turn:"), gbc);
        gbc.gridx = 1;
        maxDrawersPerTurnSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
        panel.add(maxDrawersPerTurnSpinner, gbc);

        // ── Palace wing ──
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Palace wing (empty = project name):"), gbc);
        gbc.gridx = 1;
        palaceWingField = new JTextField(20);
        panel.add(palaceWingField, gbc);

        // ── Backfill section ──
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        JSeparator sep = new JSeparator();
        panel.add(sep, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        backfillStatusLabel = new JLabel();
        updateBackfillStatus();
        panel.add(backfillStatusLabel, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        backfillButton = new JButton("Mine Existing History");
        backfillButton.setToolTipText("Mine all past conversation sessions into the memory store");
        backfillButton.addActionListener(e -> runBackfill());
        panel.add(backfillButton, gbc);

        gbc.gridx = 1;
        JLabel backfillHint = new JLabel(
            "<html><i>⚠ Can be slow if you have many sessions. " +
                "Runs in the background.</i></html>");
        backfillHint.setForeground(UIManager.getColor("Component.warningFocusColor"));
        panel.add(backfillHint, gbc);

        // ── Filler ──
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    private void updateBackfillStatus() {
        MemorySettings settings = MemorySettings.getInstance(project);
        if (settings.isBackfillCompleted()) {
            backfillStatusLabel.setText("✓ History has been mined into memory.");
        } else {
            int sessionCount = SessionStoreV2.getInstance(project)
                .listSessions(project.getBasePath()).size();
            if (sessionCount > 0) {
                backfillStatusLabel.setText(
                    "<html><b>" + sessionCount + " past sessions</b> available to mine. " +
                        "Click below to populate memory from your conversation history.</html>");
            } else {
                backfillStatusLabel.setText("No past sessions found.");
            }
        }
    }

    private void runBackfill() {
        MemorySettings settings = MemorySettings.getInstance(project);
        if (!settings.isEnabled()) {
            Messages.showWarningDialog(
                project,
                "Please enable semantic memory first, then apply settings before running the backfill.",
                "Memory Not Enabled");
            return;
        }

        backfillButton.setEnabled(false);
        backfillButton.setText("Mining…");
        backfillStatusLabel.setText("Mining in progress…");

        BackfillMiner backfillMiner = new BackfillMiner(project);
        backfillMiner.run(progress ->
            ApplicationManager.getApplication().invokeLater(() ->
                backfillStatusLabel.setText(progress)
            )
        ).whenComplete((result, error) ->
            ApplicationManager.getApplication().invokeLater(() -> {
                backfillButton.setEnabled(true);
                backfillButton.setText("Mine Existing History");
                if (error != null) {
                    backfillStatusLabel.setText("Backfill failed: " + error.getMessage());
                } else {
                    updateBackfillStatus();
                }
            })
        );
    }

    @Override
    public boolean isModified() {
        MemorySettings settings = MemorySettings.getInstance(project);
        return enabledCheckBox.isSelected() != settings.isEnabled()
            || autoMineTurnCheckBox.isSelected() != settings.isAutoMineOnTurnComplete()
            || autoMineArchiveCheckBox.isSelected() != settings.isAutoMineOnSessionArchive()
            || (int) minChunkLengthSpinner.getValue() != settings.getMinChunkLength()
            || (int) maxDrawersPerTurnSpinner.getValue() != settings.getMaxDrawersPerTurn()
            || !palaceWingField.getText().equals(settings.getPalaceWing());
    }

    @Override
    public void apply() throws ConfigurationException {
        MemorySettings settings = MemorySettings.getInstance(project);
        boolean wasDisabled = !settings.isEnabled();
        settings.setEnabled(enabledCheckBox.isSelected());
        settings.setAutoMineOnTurnComplete(autoMineTurnCheckBox.isSelected());
        settings.setAutoMineOnSessionArchive(autoMineArchiveCheckBox.isSelected());
        settings.setMinChunkLength((int) minChunkLengthSpinner.getValue());
        settings.setMaxDrawersPerTurn((int) maxDrawersPerTurnSpinner.getValue());
        settings.setPalaceWing(palaceWingField.getText().trim());

        // Offer backfill when memory is first enabled
        if (wasDisabled && settings.isEnabled() && !settings.isBackfillCompleted()) {
            offerBackfill();
        }
    }

    private void offerBackfill() {
        int sessionCount = SessionStoreV2.getInstance(project)
            .listSessions(project.getBasePath()).size();
        if (sessionCount == 0) return;

        int choice = Messages.showYesNoDialog(
            project,
            "You have " + sessionCount + " past conversation sessions.\n\n" +
                "Would you like to mine them into memory now?\n" +
                "This runs in the background but may take a while for large histories.",
            "Mine Existing History?",
            "Mine Now",
            "Later",
            Messages.getQuestionIcon());

        if (choice == Messages.YES) {
            runBackfill();
        }
    }

    @Override
    public void reset() {
        MemorySettings settings = MemorySettings.getInstance(project);
        enabledCheckBox.setSelected(settings.isEnabled());
        autoMineTurnCheckBox.setSelected(settings.isAutoMineOnTurnComplete());
        autoMineArchiveCheckBox.setSelected(settings.isAutoMineOnSessionArchive());
        minChunkLengthSpinner.setValue(settings.getMinChunkLength());
        maxDrawersPerTurnSpinner.setValue(settings.getMaxDrawersPerTurn());
        palaceWingField.setText(settings.getPalaceWing());
        if (backfillStatusLabel != null) {
            updateBackfillStatus();
        }
    }
}
