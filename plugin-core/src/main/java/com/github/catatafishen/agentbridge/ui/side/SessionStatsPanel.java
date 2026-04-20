package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.BillingDisplayData;
import com.github.catatafishen.agentbridge.ui.BillingManager;
import com.github.catatafishen.agentbridge.ui.ProcessingTimerPanel;
import com.github.catatafishen.agentbridge.ui.SessionStatsSnapshot;
import com.github.catatafishen.agentbridge.ui.TimerDisplayFormatter;
import com.github.catatafishen.agentbridge.ui.UsageGraphPanel;
import com.intellij.ui.AnimatedIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Side panel tab displaying session statistics as labeled rows: processing status,
 * session totals (time, turns, tools, lines, tokens, cost), and a thin billing
 * usage graph with quota information.
 *
 * <p>Subscribes to change callbacks from both {@link ProcessingTimerPanel} and
 * {@link BillingManager} for a single, consistent refresh model.
 */
public final class SessionStatsPanel extends JPanel {

    private static final DateTimeFormatter RESET_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final ProcessingTimerPanel timerPanel;
    private final BillingManager billing;

    // Status row (visible only while processing)
    private final JLabel spinnerLabel = new JLabel(new AnimatedIcon.Default());
    private final JLabel statusLabel = new JLabel();
    private final JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));

    // Session stats value labels
    private final JLabel timeValue = new JLabel();
    private final JLabel turnsValue = new JLabel();
    private final JLabel toolsValue = new JLabel();
    private final JLabel linesValue = new JLabel();
    private final JLabel tokensValue = new JLabel();
    private final JLabel costValue = new JLabel();

    // Dynamic labels whose text changes based on provider mode
    private final JLabel tokensRowLabel = new JLabel("Tokens");
    private final JLabel costRowLabel = new JLabel("Cost");
    private final JPanel tokensRow;
    private final JPanel costRow;

    // Billing section widgets
    private final JLabel usageValue = new JLabel();
    private final JLabel remainingValue = new JLabel();
    private final JLabel resetsValue = new JLabel();
    private final JPanel usageRow;
    private final JPanel remainingRow;
    private final JPanel resetsRow;
    private final JPanel billingHeader;

    public SessionStatsPanel(
        @NotNull ProcessingTimerPanel timerPanel,
        @NotNull UsageGraphPanel usageGraphPanel,
        @NotNull BillingManager billing
    ) {
        super(new BorderLayout());
        this.timerPanel = timerPanel;
        this.billing = billing;

        Font smallFont = UIManager.getFont("Label.font").deriveFont((float) JBUI.scale(11));
        Color dimColor = JBUI.CurrentTheme.Label.disabledForeground();

        // Status indicator row
        statusRow.setOpaque(false);
        statusRow.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(6), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        spinnerLabel.setVisible(false);
        statusLabel.setFont(smallFont);
        statusLabel.setForeground(dimColor);
        statusRow.add(spinnerLabel);
        statusRow.add(statusLabel);
        statusRow.setVisible(false);

        // Session stats grid
        JPanel statsGrid = new JPanel(new GridBagLayout());
        statsGrid.setOpaque(false);
        statsGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int row = 0;
        addStatRow(statsGrid, row++, "Time", timeValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Turns", turnsValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Tool calls", toolsValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Lines changed", linesValue, smallFont, dimColor);

        tokensRow = addStatRowWithLabel(statsGrid, row++, tokensRowLabel, tokensValue, smallFont, dimColor);
        costRow = addStatRowWithLabel(statsGrid, row, costRowLabel, costValue, smallFont, dimColor);

        // Usage graph — thin full-width sparkline
        JPanel graphSection = new JPanel(new BorderLayout());
        graphSection.setOpaque(false);
        graphSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        int graphH = JBUI.scale(20);
        usageGraphPanel.setPreferredSize(new Dimension(0, graphH));
        usageGraphPanel.setMinimumSize(new Dimension(0, graphH));
        usageGraphPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, graphH));
        graphSection.add(usageGraphPanel, BorderLayout.CENTER);

        // Billing stats grid
        JPanel billingGrid = new JPanel(new GridBagLayout());
        billingGrid.setOpaque(false);
        billingGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)));

        billingHeader = createSectionHeader("Monthly quota", smallFont, dimColor);
        int brow = 0;
        usageRow = addStatRow(billingGrid, brow++, "Used", usageValue, smallFont, dimColor);
        remainingRow = addStatRow(billingGrid, brow++, "Remaining", remainingValue, smallFont, dimColor);
        resetsRow = addStatRow(billingGrid, brow, "Resets", resetsValue, smallFont, dimColor);

        // Assemble the content
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(statusRow);
        content.add(createSectionHeader("Session", smallFont, dimColor));
        content.add(statsGrid);
        content.add(billingHeader);
        content.add(graphSection);
        content.add(billingGrid);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);

        add(wrapper, BorderLayout.CENTER);

        timerPanel.setOnStatsChanged(this::refresh);
        billing.setOnBillingChanged(this::refresh);
        refresh();
    }

    private JPanel createSectionHeader(String title, Font font, Color color) {
        JLabel label = new JLabel(title);
        label.setFont(font.deriveFont(Font.BOLD));
        label.setForeground(color);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(6), 0, JBUI.scale(2), 0));
        header.add(label);
        return header;
    }

    @SuppressWarnings("SameParameterValue")
    private JPanel addStatRow(JPanel grid, int row, String labelText, JLabel value,
                              Font font, Color dimColor) {
        return addStatRowWithLabel(grid, row, new JLabel(labelText), value, font, dimColor);
    }

    private JPanel addStatRowWithLabel(JPanel grid, int row, JLabel label, JLabel value,
                                       Font font, Color dimColor) {
        label.setFont(font);
        label.setForeground(dimColor);
        value.setFont(font);

        JPanel rowPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rowPanel.setOpaque(false);
        rowPanel.add(label, BorderLayout.WEST);
        rowPanel.add(value, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, JBUI.scale(2), 0);
        grid.add(rowPanel, gbc);
        return rowPanel;
    }

    private void refresh() {
        SessionStatsSnapshot snap = timerPanel.getSessionSnapshot();
        BillingDisplayData bill = billing.getBillingDisplayData();

        refreshStatus(snap);
        refreshSessionStats(snap);
        refreshBilling(bill);

        revalidate();
        repaint();
    }

    private void refreshStatus(SessionStatsSnapshot snap) {
        if (snap.isRunning()) {
            spinnerLabel.setVisible(true);
            String elapsed = TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getTurnElapsedSec());
            statusLabel.setText("Processing… " + elapsed);
            statusRow.setVisible(true);
        } else {
            spinnerLabel.setVisible(false);
            statusRow.setVisible(false);
        }
    }

    private void refreshSessionStats(SessionStatsSnapshot snap) {
        timeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getSessionTotalTimeSec()));
        turnsValue.setText(String.valueOf(snap.getSessionTurnCount()));
        toolsValue.setText(String.valueOf(snap.getSessionToolCalls()));

        String lines = TimerDisplayFormatter.INSTANCE.formatLinesChanged(
            snap.getSessionLinesAdded(), snap.getSessionLinesRemoved());
        linesValue.setText(lines.isEmpty() ? "—" : lines);

        if (snap.getMultiplierMode()) {
            tokensRowLabel.setText("Premium req");
            tokensValue.setText(String.valueOf(snap.getSessionTurnCount()));
            tokensRow.setVisible(true);
            costRow.setVisible(false);
        } else {
            long totalTokens = snap.getSessionInputTokens() + snap.getSessionOutputTokens();
            if (totalTokens > 0 || snap.getSessionCostUsd() > 0.0) {
                tokensRowLabel.setText("Tokens");
                tokensValue.setText(
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionInputTokens()) +
                        " in / " +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionOutputTokens()) +
                        " out");
                tokensRow.setVisible(true);
                costRowLabel.setText("Cost");
                costValue.setText(TimerDisplayFormatter.INSTANCE.formatCost(snap.getSessionCostUsd()));
                costRow.setVisible(true);
            } else {
                tokensRow.setVisible(false);
                costRow.setVisible(false);
            }
        }
    }

    private void refreshBilling(BillingDisplayData bill) {
        boolean hasBilling = bill.getEntitlement() > 0 || bill.getUnlimited();
        billingHeader.setVisible(hasBilling);

        if (bill.getUnlimited()) {
            usageValue.setText("Unlimited");
            usageRow.setVisible(true);
            remainingRow.setVisible(false);
        } else if (bill.getEntitlement() > 0) {
            usageValue.setText(bill.getEstimatedUsed() + " / " + bill.getEntitlement());
            usageRow.setVisible(true);
            int remaining = bill.getEstimatedRemaining();
            if (remaining < 0) {
                remainingValue.setText("Over by " + (-remaining));
                remainingValue.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            } else {
                remainingValue.setText(String.valueOf(remaining));
                remainingValue.setForeground(UIManager.getColor("Label.foreground"));
            }
            remainingRow.setVisible(true);
        } else {
            usageRow.setVisible(false);
            remainingRow.setVisible(false);
        }

        if (!bill.getResetDate().isEmpty()) {
            try {
                LocalDate reset = LocalDate.parse(bill.getResetDate(), DateTimeFormatter.ISO_LOCAL_DATE);
                resetsValue.setText(reset.format(RESET_DATE_FMT));
                resetsRow.setVisible(hasBilling);
            } catch (Exception ignored) {
                resetsRow.setVisible(false);
            }
        } else {
            resetsRow.setVisible(false);
        }
    }
}
