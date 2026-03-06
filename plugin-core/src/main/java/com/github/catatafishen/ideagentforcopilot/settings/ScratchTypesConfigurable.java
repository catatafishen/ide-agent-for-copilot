package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings page for configuring scratch file language-to-extension mappings.
 * Appears under Settings > Tools > IDE Agent for Copilot > Scratch File Types.
 */
public final class ScratchTypesConfigurable implements Configurable {

    private JBTable table;
    private MappingsTableModel tableModel;

    @Nls
    @Override
    public String getDisplayName() {
        return "Scratch File Types";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        tableModel = new MappingsTableModel();
        loadFromSettings();

        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);

        JPanel decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction(b -> {
                tableModel.addRow("", "");
                int row = tableModel.getRowCount() - 1;
                table.editCellAt(row, 0);
                table.getSelectionModel().setSelectionInterval(row, row);
            })
            .setRemoveAction(b -> {
                int row = table.getSelectedRow();
                if (row >= 0) tableModel.removeRow(row);
            })
            .createPanel();

        JPanel panel = new JPanel(new BorderLayout());
        JBLabel hint = new JBLabel(
            "<html>Map language aliases to file extensions for the \"Open in Scratch\" button.<br>"
                + "Multiple aliases can map to the same extension (e.g., <code>kotlin</code> → <code>kt</code>).</html>");
        hint.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(decorated, BorderLayout.CENTER);

        return panel;
    }

    @Override
    public boolean isModified() {
        Map<String, String> current = ScratchTypeSettings.getInstance().getMappings();
        Map<String, String> edited = tableModel.toMap();
        return !current.equals(edited);
    }

    @Override
    public void apply() {
        ScratchTypeSettings.getInstance().setMappings(tableModel.toMap());
    }

    @Override
    public void reset() {
        loadFromSettings();
    }

    @Override
    public void disposeUIResources() {
        table = null;
        tableModel = null;
    }

    private void loadFromSettings() {
        if (tableModel == null) return;
        tableModel.clear();
        Map<String, String> mappings = ScratchTypeSettings.getInstance().getMappings();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            tableModel.addRow(entry.getKey(), entry.getValue());
        }
    }

    private static final class MappingsTableModel extends AbstractTableModel {
        private final List<String[]> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Language Alias" : "Extension";
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            rows.get(row)[column] = value.toString().trim().toLowerCase();
            fireTableCellUpdated(row, column);
        }

        void addRow(String alias, String extension) {
            rows.add(new String[]{alias, extension});
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeRow(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        void clear() {
            int size = rows.size();
            if (size > 0) {
                rows.clear();
                fireTableRowsDeleted(0, size - 1);
            }
        }

        Map<String, String> toMap() {
            Map<String, String> map = new LinkedHashMap<>();
            for (String[] row : rows) {
                String alias = row[0].trim().toLowerCase();
                String ext = row[1].trim().toLowerCase();
                if (!alias.isEmpty() && !ext.isEmpty()) {
                    map.put(alias, ext);
                }
            }
            return map;
        }
    }
}
