package com.github.copilot.intellij.ui

import com.github.copilot.intellij.services.CopilotSettings
import com.github.copilot.intellij.services.ToolPermission
import com.github.copilot.intellij.services.ToolRegistry
import com.github.copilot.intellij.services.ToolRegistry.Category
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

private val PERM_OPTIONS = arrayOf("Always Allow", "Ask", "Always Deny")
private fun ToolPermission.toIndex() = when (this) { ToolPermission.ALLOW -> 0; ToolPermission.ASK -> 1; ToolPermission.DENY -> 2 }
private fun Int.toPermission() = when (this) { 0 -> ToolPermission.ALLOW; 1 -> ToolPermission.ASK; else -> ToolPermission.DENY }

private const val BUILTIN_TOOLTIP = "<html>Cannot be disabled — GitHub Copilot CLI injects these tools regardless<br>" +
        "of settings (ACP bug #556: github.com/github/copilot-cli/issues/556)</html>"

/**
 * Scrollable panel listing all tools with per-tool permission settings.
 * Shown as the "Permissions" tab inside the settings dialog.
 */
internal class PermissionsPanel {

    private data class ToolRow(
        val toolId: String,
        val enabledBox: JCheckBox,
        val permCombo: JComboBox<String>,
        val inProjectCombo: JComboBox<String>?,
        val outProjectCombo: JComboBox<String>?,
    )

    private val rows = mutableListOf<ToolRow>()
    val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(4, 8, 8, 8)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        // Header row
        gbc.gridwidth = 4; gbc.weightx = 0.0
        val headerPanel = JBPanel<JBPanel<*>>(GridLayout(1, 4)).apply {
            border = JBUI.Borders.empty(4, 0, 6, 0)
            add(JBLabel("<html><b>Tool</b></html>").also { it.border = JBUI.Borders.emptyLeft(24) })
            add(JBLabel(""))  // enabled col spacer
            add(JBLabel("<html><b>Permission</b></html>").also { it.preferredSize = Dimension(120, 20) })
            add(JBLabel(""))
        }
        content.add(headerPanel, gbc)
        gbc.gridy++

        // Add separator
        gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        content.add(JSeparator(), gbc)
        gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        var lastCategory: Category? = null

        for (tool in ToolRegistry.getAllTools()) {
            // Category header
            if (tool.category != lastCategory) {
                lastCategory = tool.category
                gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                gbc.insets = JBUI.insets(8, 0, 2, 0)
                val catLabel = JBLabel("<html><b>${tool.category.displayName}</b></html>")
                catLabel.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                catLabel.border = JBUI.Borders.emptyTop(4)
                content.add(catLabel, gbc)
                gbc.gridy++
                gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.insets(1, 0)
            }

            // Tool row
            val enabledBox = JCheckBox().apply {
                isOpaque = false
                if (tool.isBuiltIn) {
                    isSelected = false; isEnabled = false; toolTipText = BUILTIN_TOOLTIP
                } else {
                    isSelected = CopilotSettings.getToolPermission(tool.id) != ToolPermission.DENY
                    toolTipText = "Enable or disable this tool"
                }
            }

            val permCombo = JComboBox(PERM_OPTIONS).apply {
                preferredSize = Dimension(JBUI.scale(128), preferredSize.height)
                if (tool.isBuiltIn) {
                    isEnabled = false; toolTipText = BUILTIN_TOOLTIP
                    selectedIndex = ToolPermission.DENY.toIndex()
                } else {
                    selectedIndex = CopilotSettings.getToolPermission(tool.id).toIndex()
                    // Sync enabled checkbox <-> combo
                    addActionListener {
                        enabledBox.isSelected = selectedIndex.toPermission() != ToolPermission.DENY
                    }
                    enabledBox.addActionListener {
                        if (enabledBox.isSelected && selectedIndex == ToolPermission.DENY.toIndex()) {
                            selectedIndex = ToolPermission.ALLOW.toIndex()
                        } else if (!enabledBox.isSelected) {
                            selectedIndex = ToolPermission.DENY.toIndex()
                        }
                    }
                }
            }

            val inProjectCombo: JComboBox<String>?
            val outProjectCombo: JComboBox<String>?
            if (tool.supportsPathSubPermissions && !tool.isBuiltIn) {
                inProjectCombo = JComboBox(PERM_OPTIONS).apply {
                    preferredSize = Dimension(JBUI.scale(110), preferredSize.height)
                    selectedIndex = CopilotSettings.getToolPermissionInsideProject(tool.id).toIndex()
                    toolTipText = "Permission when path is inside the project"
                }
                outProjectCombo = JComboBox(PERM_OPTIONS).apply {
                    preferredSize = Dimension(JBUI.scale(110), preferredSize.height)
                    selectedIndex = CopilotSettings.getToolPermissionOutsideProject(tool.id).toIndex()
                    toolTipText = "Permission when path is outside the project"
                }
            } else {
                inProjectCombo = null; outProjectCombo = null
            }

            rows.add(ToolRow(tool.id, enabledBox, permCombo, inProjectCombo, outProjectCombo))

            // Layout the tool row: [enabled] [name label] [perm combo]
            gbc.gridwidth = 1; gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE; gbc.gridx = 0
            content.add(enabledBox, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            val nameLabel = JBLabel(tool.displayName).apply {
                if (tool.isBuiltIn) foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(4)
            }
            content.add(nameLabel, gbc)

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            content.add(permCombo, gbc)

            gbc.gridx = 3
            content.add(JBPanel<JBPanel<*>>(), gbc) // spacer
            gbc.gridy++

            // Sub-permission rows for path-aware tools
            if (inProjectCombo != null && outProjectCombo != null) {
                addSubPermRow(content, gbc, "\u00a0\u00a0\u00a0\u25b8 Inside project:", inProjectCombo)
                addSubPermRow(content, gbc, "\u00a0\u00a0\u00a0\u25b8 Outside project:", outProjectCombo)
            }
        }

        // Bottom spacer
        gbc.gridwidth = 4; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0
        content.add(JBPanel<JBPanel<*>>(), gbc)

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun addSubPermRow(
        panel: JBPanel<*>, gbc: GridBagConstraints,
        label: String, combo: JComboBox<String>
    ) {
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridx = 1
        gbc.insets = JBUI.insets(0, 24, 0, 0)
        panel.add(JBLabel(label).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = JBColor.GRAY
        }, gbc)
        gbc.gridx = 2; gbc.insets = JBUI.insets(1, 0)
        panel.add(combo, gbc)
        gbc.gridx = 3; panel.add(JBPanel<JBPanel<*>>(), gbc)
        gbc.gridy++; gbc.insets = JBUI.insets(1, 0)
    }

    /** Persist all settings from the current UI state. */
    fun save() {
        for (row in rows) {
            val perm = row.permCombo.selectedIndex.toPermission()
            CopilotSettings.setToolPermission(row.toolId, perm)
            row.inProjectCombo?.let { CopilotSettings.setToolPermissionInsideProject(row.toolId, it.selectedIndex.toPermission()) }
            row.outProjectCombo?.let { CopilotSettings.setToolPermissionOutsideProject(row.toolId, it.selectedIndex.toPermission()) }
        }
    }
}
