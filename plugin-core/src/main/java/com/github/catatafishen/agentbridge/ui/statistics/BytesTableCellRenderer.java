package com.github.catatafishen.agentbridge.ui.statistics;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Renders a {@code Long} byte value as a human-readable size (e.g., "1.5 KB", "2.0 MB").
 * Right-aligned to match numeric column conventions.
 */
final class BytesTableCellRenderer extends DefaultTableCellRenderer {

    BytesTableCellRenderer() {
        setHorizontalAlignment(SwingConstants.RIGHT);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        if (value instanceof Long bytes) {
            value = ToolStatisticsTableModel.formatBytes(bytes);
        }
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }
}
