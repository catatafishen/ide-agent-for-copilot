package com.github.copilot.intellij.ui

import com.github.copilot.intellij.services.CopilotSettings
import com.github.copilot.intellij.services.ToolPermission
import com.github.copilot.intellij.services.ToolRegistry
import com.github.copilot.intellij.services.ToolRegistry.Category
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

// Permission options for different tool types
private val PLUGIN_PERM_OPTIONS = arrayOf("Allow", "Ask")
private val BUILTIN_PERM_OPTIONS = arrayOf("Allow", "Ask", "Deny")

private fun ToolPermission.toPluginIndex() = when (this) {
    ToolPermission.ASK -> 1; else -> 0
}

private fun ToolPermission.toBuiltinIndex() = when (this) {
    ToolPermission.ALLOW -> 0; ToolPermission.ASK -> 1; else -> 2
}

private fun Int.toPluginPermission() = if (this == 1) ToolPermission.ASK else ToolPermission.ALLOW
private fun Int.toBuiltinPermission() = when (this) {
    0 -> ToolPermission.ALLOW; 1 -> ToolPermission.ASK; else -> ToolPermission.DENY
}

private const val BUILTIN_TOOLTIP = "<html>Cannot be disabled — GitHub Copilot CLI injects these tools<br>" +
    "regardless of settings (ACP bug #556)</html>"
private const val SILENT_TOOLTIP = "<html>This tool runs without a permission request — no control available</html>"
private const val RESTART_NOTE =
    "<html><i>\u26a0 Changes to tool enabled/disabled take effect after restarting the agent session.</i></html>"

/**
 * Scrollable panel listing all tools with per-tool permission settings.
 * Shown as the "Permissions" tab inside the settings dialog.
 */
internal class PermissionsPanel {

    private data class ToolRow(
        val toolId: String,
        val isPlugin: Boolean,
        val enabledBox: JCheckBox?,          // null for built-ins
        val permCombo: JComboBox<String>?,   // null for silent built-ins
        val inProjectCombo: JComboBox<String>?,
        val outProjectCombo: JComboBox<String>?,
    )

    private val rows = mutableListOf<ToolRow>()
    val component: JComponent = buildPanel()

    @Suppress("CognitiveComplexMethod")
    private fun buildPanel(): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(4, 8, 8, 8)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        // Restart note at the top
        gbc.gridwidth = 4; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        val noteLabel = JBLabel(RESTART_NOTE).apply {
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            border = JBUI.Borders.empty(2, 0, 6, 0)
        }
        content.add(noteLabel, gbc)
        gbc.gridy++

        // Separator
        content.add(JSeparator(), gbc)
        gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        var lastCategory: Category? = null
        var lastIsBuiltIn: Boolean? = null

        for (tool in ToolRegistry.getAllTools()) {
            // Top-level section break when transitioning between built-in and plugin tools
            if (tool.isBuiltIn != lastIsBuiltIn) {
                lastIsBuiltIn = tool.isBuiltIn
                lastCategory = null  // reset category so it re-renders below
                gbc.gridx = 0; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                gbc.insets = JBUI.insets(12, 0, 2, 0)
                val sectionTitle = if (tool.isBuiltIn) "Built-in Tools (GitHub Copilot CLI)"
                else "IntelliJ Plugin Tools"
                content.add(TitledSeparator("<html><b>$sectionTitle</b></html>"), gbc)
                gbc.gridy++
                gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.insets(1, 0)
            }

            // Category header
            if (tool.category != lastCategory) {
                lastCategory = tool.category
                gbc.gridx = 0; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                gbc.insets = JBUI.insets(8, 16, 2, 0)
                content.add(TitledSeparator(tool.category.displayName), gbc)
                gbc.gridy++
                gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.insets(1, 0)
            }

            val enabledBox: JCheckBox?
            val permCombo: JComboBox<String>?

            when {
                // ── Plugin tool — can be enabled/disabled + Allow/Ask ─────────────
                !tool.isBuiltIn -> {
                    val enabled = CopilotSettings.isToolEnabled(tool.id)
                    enabledBox = JCheckBox().apply {
                        isOpaque = false
                        isSelected = enabled
                        toolTipText = "Enable or disable this tool (requires agent restart)"
                    }
                    permCombo = JComboBox(PLUGIN_PERM_OPTIONS).apply {
                        preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                        isEnabled = enabled
                        selectedIndex = CopilotSettings.getToolPermission(tool.id).toPluginIndex()
                        toolTipText = "Permission when agent requests this tool"
                    }
                    enabledBox.addActionListener {
                        permCombo.isEnabled = enabledBox.isSelected
                    }
                }

                // ── Built-in with deny control — Allow/Ask/Deny ────────────────
                tool.hasDenyControl -> {
                    enabledBox = JCheckBox().apply {
                        isOpaque = false; isSelected = true; isEnabled = false
                        toolTipText = BUILTIN_TOOLTIP
                    }
                    permCombo = JComboBox(BUILTIN_PERM_OPTIONS).apply {
                        preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                        selectedIndex = CopilotSettings.getToolPermission(tool.id).toBuiltinIndex()
                        toolTipText = "Permission when agent requests this tool"
                    }
                }

                // ── Built-in silent (read tools) — no control ──────────────────
                else -> {
                    enabledBox = JCheckBox().apply {
                        isOpaque = false; isSelected = true; isEnabled = false
                        toolTipText = BUILTIN_TOOLTIP
                    }
                    permCombo = null
                }
            }

            val inProjectCombo: JComboBox<String>?
            val outProjectCombo: JComboBox<String>?
            if (tool.supportsPathSubPermissions && !tool.isBuiltIn) {
                val options = PLUGIN_PERM_OPTIONS
                inProjectCombo = JComboBox(options).apply {
                    preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                    selectedIndex = CopilotSettings.getToolPermissionInsideProject(tool.id).toPluginIndex()
                    toolTipText = "Permission when path is inside the project"
                    isEnabled = CopilotSettings.isToolEnabled(tool.id)
                }
                outProjectCombo = JComboBox(options).apply {
                    preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                    selectedIndex = CopilotSettings.getToolPermissionOutsideProject(tool.id).toPluginIndex()
                    toolTipText = "Permission when path is outside the project"
                    isEnabled = CopilotSettings.isToolEnabled(tool.id)
                }
                enabledBox.addActionListener {
                    val on = enabledBox.isSelected
                    inProjectCombo.isEnabled = on
                    outProjectCombo.isEnabled = on
                }
            } else {
                inProjectCombo = null; outProjectCombo = null
            }

            rows.add(ToolRow(tool.id, !tool.isBuiltIn, enabledBox, permCombo, inProjectCombo, outProjectCombo))

            // Layout: [enabledBox] [name label] [perm combo OR "runs silently"]
            gbc.gridwidth = 1; gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE; gbc.gridx = 0
            content.add(enabledBox, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            val badge = if (tool.isBuiltIn) " <small>[Built-in]</small>" else ""
            val nameLabel = JBLabel("<html>${tool.displayName}$badge</html>").apply {
                if (tool.isBuiltIn) foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(4)
                if (tool.description.isNotEmpty()) toolTipText = "<html>${tool.description}</html>"
            }
            content.add(nameLabel, gbc)

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            if (permCombo != null) {
                content.add(permCombo, gbc)
            } else {
                val silentLabel = JBLabel("\uD83D\uDD12 runs silently").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(font.size2D - 1f)
                    toolTipText = SILENT_TOOLTIP
                }
                content.add(silentLabel, gbc)
            }

            gbc.gridx = 3; content.add(JBPanel<JBPanel<*>>(), gbc)
            gbc.gridy++

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
            if (row.isPlugin) {
                // Save enabled flag for plugin tools
                row.enabledBox?.let { CopilotSettings.setToolEnabled(row.toolId, it.isSelected) }
                // Save Allow/Ask permission
                row.permCombo?.let {
                    CopilotSettings.setToolPermission(row.toolId, it.selectedIndex.toPluginPermission())
                }
                row.inProjectCombo?.let {
                    CopilotSettings.setToolPermissionInsideProject(row.toolId, it.selectedIndex.toPluginPermission())
                }
                row.outProjectCombo?.let {
                    CopilotSettings.setToolPermissionOutsideProject(row.toolId, it.selectedIndex.toPluginPermission())
                }
            } else {
                // Built-in with deny control — save Allow/Ask/Deny
                row.permCombo?.let {
                    CopilotSettings.setToolPermission(row.toolId, it.selectedIndex.toBuiltinPermission())
                }
            }
        }
    }
}
