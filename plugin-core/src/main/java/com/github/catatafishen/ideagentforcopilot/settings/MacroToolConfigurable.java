package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.MacroToolRegistrar;
import com.github.catatafishen.ideagentforcopilot.services.MacroToolSettings;
import com.github.catatafishen.ideagentforcopilot.services.MacroToolSettings.MacroRegistration;
import com.intellij.ide.actionMacro.ActionMacro;
import com.intellij.ide.actionMacro.ActionMacroManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings panel at Settings > Tools > Macro Tools.
 * Lets users register recorded macros as MCP tools with custom names and descriptions.
 */
public final class MacroToolConfigurable implements Configurable {

    private static final int COL_ENABLED = 0;
    private static final int COL_MACRO = 1;
    private static final int COL_TOOL_NAME = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_COUNT = 4;

    private final Project project;
    private JPanel mainPanel;
    private MacroTableModel tableModel;
    private JBTable table;

    public MacroToolConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Macro Tools";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));

        // Info label
        JBLabel info = new JBLabel(
            "<html>Register recorded macros as MCP tools. "
                + "Record macros via <b>Edit → Macros → Start Macro Recording</b>.</html>");
        info.setBorder(JBUI.Borders.emptyBottom(4));
        mainPanel.add(info, BorderLayout.NORTH);

        // Table
        tableModel = new MacroTableModel();
        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(COL_ENABLED).setMaxWidth(JBUI.scale(60));
        table.getColumnModel().getColumn(COL_ENABLED).setMinWidth(JBUI.scale(60));
        table.getColumnModel().getColumn(COL_MACRO).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(COL_TOOL_NAME).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(COL_DESCRIPTION).setPreferredWidth(JBUI.scale(300));
        table.setRowHeight(JBUI.scale(24));

        JBScrollPane scrollPane = new JBScrollPane(table);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton addButton = new JButton("Register Macro...");
        addButton.addActionListener(e -> addMacro());
        JButton removeButton = new JButton("Remove");
        removeButton.addActionListener(e -> removeSelected());
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        reset();
        return mainPanel;
    }

    private void addMacro() {
        ActionMacro[] macros = ActionMacroManager.getInstance().getAllMacros();
        if (macros.length == 0) {
            JOptionPane.showMessageDialog(mainPanel,
                "No recorded macros found.\n"
                    + "Record one via Edit → Macros → Start Macro Recording.",
                "No Macros", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Filter out already-registered macros
        Set<String> alreadyRegistered = tableModel.rows.stream()
            .map(r -> r.macroName)
            .collect(Collectors.toSet());

        List<String> available = new ArrayList<>();
        for (ActionMacro m : macros) {
            if (!alreadyRegistered.contains(m.getName())) {
                available.add(m.getName());
            }
        }

        if (available.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel,
                "All recorded macros are already registered.",
                "No New Macros", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String selected = (String) JOptionPane.showInputDialog(mainPanel,
            "Select a macro to register as an MCP tool:",
            "Register Macro",
            JOptionPane.PLAIN_MESSAGE,
            null,
            available.toArray(new String[0]),
            available.get(0));

        if (selected != null) {
            String toolName = MacroToolRegistrar.sanitizeToolName(selected);
            MacroRegistration reg = new MacroRegistration(selected, toolName, "", true);
            tableModel.rows.add(reg);
            tableModel.fireTableRowsInserted(tableModel.rows.size() - 1, tableModel.rows.size() - 1);
        }
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            tableModel.rows.remove(row);
            tableModel.fireTableRowsDeleted(row, row);
        }
    }

    @Override
    public boolean isModified() {
        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        return !tableModel.rows.equals(settings.getRegistrations());
    }

    @Override
    public void apply() throws com.intellij.openapi.options.ConfigurationException {
        // Validate: no duplicate tool names
        Set<String> names = new java.util.HashSet<>();
        for (MacroRegistration reg : tableModel.rows) {
            if (!reg.toolName.isEmpty() && !names.add(reg.toolName)) {
                throw new com.intellij.openapi.options.ConfigurationException(
                    "Duplicate tool name: " + reg.toolName);
            }
        }

        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        settings.setRegistrations(tableModel.rows.stream()
            .map(MacroRegistration::copy)
            .collect(Collectors.toList()));

        MacroToolRegistrar.getInstance(project).syncRegistrations();
    }

    @Override
    public void reset() {
        if (tableModel == null) return;
        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        tableModel.rows = new ArrayList<>(
            settings.getRegistrations().stream()
                .map(MacroRegistration::copy)
                .collect(Collectors.toList()));
        tableModel.fireTableDataChanged();
    }

    private static final class MacroTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Enabled", "Macro", "Tool Name", "Description"};
        List<MacroRegistration> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COL_COUNT;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_ENABLED ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Macro name is read-only; tool name, description, and enabled are editable
            return columnIndex != COL_MACRO;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MacroRegistration reg = rows.get(rowIndex);
            return switch (columnIndex) {
                case COL_ENABLED -> reg.enabled;
                case COL_MACRO -> reg.macroName;
                case COL_TOOL_NAME -> reg.toolName;
                case COL_DESCRIPTION -> reg.description;
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            MacroRegistration reg = rows.get(rowIndex);
            switch (columnIndex) {
                case COL_ENABLED -> reg.enabled = (Boolean) aValue;
                case COL_TOOL_NAME -> reg.toolName = ((String) aValue).trim();
                case COL_DESCRIPTION -> reg.description = ((String) aValue).trim();
                default -> { /* macro name is read-only */ }
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
