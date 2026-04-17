package com.github.catatafishen.agentbridge.ui.statistics;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Renders a {@code Double} value as a one-decimal-place percentage string (e.g., "12.5").
 * Right-aligned to match numeric column conventions.
 */
final class PercentTableCellRenderer extends DefaultTableCellRenderer {

    PercentTableCellRenderer() {
        setHorizontalAlignment(SwingConstants.RIGHT);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        if (value instanceof Double pct) {
            value = String.format("%.1f", pct);
        }
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }
}
