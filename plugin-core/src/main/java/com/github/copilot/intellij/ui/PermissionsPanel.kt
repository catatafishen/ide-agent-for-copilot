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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

// Permission options
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

private const val BUILTIN_TOOLTIP =
    "<html>Cannot be disabled — GitHub Copilot CLI injects these tools regardless of settings</html>"
private const val SILENT_TOOLTIP =
    "<html>This tool runs without a permission request \u2014 no control available</html>"
private const val RESTART_NOTE =
    "<html><i>\u26a0 Changes to enabled/disabled take effect after restarting the agent session.</i></html>"

/** Navigation node user-object for the tree. */
private sealed class NavNode(val label: String) {
    class Section(val isBuiltIn: Boolean) :
        NavNode(if (isBuiltIn) "Built-in Tools" else "IntelliJ Plugin Tools")

    class Cat(val category: Category, val isBuiltIn: Boolean) : NavNode(category.displayName)

    override fun toString() = label
}

/**
 * Split-panel showing tool permissions:
 * left  = navigation tree (sections + categories),
 * right = scrollable grid for the selected set of tools.
 */
internal class PermissionsPanel {

    private data class ToolRow(
        val tool: ToolRegistry.ToolEntry,
        val isPlugin: Boolean,
        val enabledBox: JCheckBox?,
        val permCombo: JComboBox<String>?,
        val inProjectCombo: JComboBox<String>?,
        val outProjectCombo: JComboBox<String>?,
    )

    private val rows = mutableListOf<ToolRow>()
    private val rightContent = JBPanel<JBPanel<*>>(BorderLayout())
    val component: JComponent

    init {
        buildAllRows()
        component = buildMainComponent()
    }

    // ── Row construction ──────────────────────────────────────────────────────

    private fun buildAllRows() {
        for (tool in ToolRegistry.getAllTools()) {
            val enabledBox: JCheckBox?
            val permCombo: JComboBox<String>?

            when {
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
                    enabledBox.addActionListener { permCombo.isEnabled = enabledBox.isSelected }
                }

                tool.hasDenyControl -> {
                    enabledBox = null
                    permCombo = JComboBox(BUILTIN_PERM_OPTIONS).apply {
                        preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                        selectedIndex = CopilotSettings.getToolPermission(tool.id).toBuiltinIndex()
                        toolTipText = BUILTIN_TOOLTIP
                    }
                }

                else -> {
                    enabledBox = null
                    permCombo = null
                }
            }

            val inProjectCombo: JComboBox<String>?
            val outProjectCombo: JComboBox<String>?
            if (tool.supportsPathSubPermissions && !tool.isBuiltIn && enabledBox != null) {
                inProjectCombo = JComboBox(PLUGIN_PERM_OPTIONS).apply {
                    preferredSize = Dimension(JBUI.scale(108), preferredSize.height)
                    selectedIndex = CopilotSettings.getToolPermissionInsideProject(tool.id).toPluginIndex()
                    toolTipText = "Permission when path is inside the project"
                    isEnabled = CopilotSettings.isToolEnabled(tool.id)
                }
                outProjectCombo = JComboBox(PLUGIN_PERM_OPTIONS).apply {
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

            rows.add(ToolRow(tool, !tool.isBuiltIn, enabledBox, permCombo, inProjectCombo, outProjectCombo))
        }
    }

    // ── Main split layout ─────────────────────────────────────────────────────

    private fun buildMainComponent(): JComponent {
        val tree = buildNavTree()
        val treeScroll = JBScrollPane(tree).apply {
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 0, 1
            )
            preferredSize = Dimension(JBUI.scale(185), 0)
            minimumSize = Dimension(JBUI.scale(140), 0)
        }

        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightContent).apply {
            dividerSize = JBUI.scale(1)
            dividerLocation = JBUI.scale(185)
            isOneTouchExpandable = false
            border = JBUI.Borders.empty()
        }
    }

    // ── Navigation tree ───────────────────────────────────────────────────────

    private fun buildNavTree(): JTree {
        val builtInNode = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = true))
        val pluginNode = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = false))

        rows.filter { !it.isPlugin }.map { it.tool.category }.distinct()
            .forEach { builtInNode.add(DefaultMutableTreeNode(NavNode.Cat(it, true))) }
        rows.filter { it.isPlugin }.map { it.tool.category }.distinct()
            .forEach { pluginNode.add(DefaultMutableTreeNode(NavNode.Cat(it, false))) }

        val root = DefaultMutableTreeNode()
        root.add(builtInNode)
        root.add(pluginNode)

        val tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            border = JBUI.Borders.empty(4)
        }

        val builtInPath = TreePath(arrayOf<Any>(root, builtInNode))
        val pluginPath = TreePath(arrayOf<Any>(root, pluginNode))
        tree.expandPath(builtInPath)
        tree.expandPath(pluginPath)

        tree.addTreeSelectionListener {
            val node = it.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val nav = node.userObject) {
                is NavNode.Section -> showTools { row -> row.isPlugin == !nav.isBuiltIn }
                is NavNode.Cat -> showTools { row ->
                    row.tool.category == nav.category && row.isPlugin == !nav.isBuiltIn
                }
            }
        }

        // Default: show IntelliJ Plugin Tools
        tree.selectionPath = pluginPath
        return tree
    }

    // ── Content panel ─────────────────────────────────────────────────────────

    private fun showTools(filter: (ToolRow) -> Boolean) {
        val filtered = rows.filter(filter)
        rightContent.removeAll()
        rightContent.add(buildContentPanel(filtered), BorderLayout.CENTER)
        rightContent.revalidate()
        rightContent.repaint()
    }

    private fun buildContentPanel(filtered: List<ToolRow>): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(4, 8, 8, 8)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        // Restart note
        gbc.gridwidth = 4; gbc.weightx = 1.0
        content.add(JBLabel(RESTART_NOTE).apply {
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            border = JBUI.Borders.empty(2, 0, 6, 0)
        }, gbc)
        gbc.gridy++
        content.add(JSeparator(), gbc)
        gbc.gridy++; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

        var lastCategory: Category? = null
        for (row in filtered) {
            if (row.tool.category != lastCategory) {
                lastCategory = row.tool.category
                gbc.gridx = 0; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                gbc.insets = JBUI.insets(8, 0, 2, 0)
                content.add(TitledSeparator(row.tool.category.displayName), gbc)
                gbc.gridy++
                gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.insets(1, 0)
            }

            // [checkbox] [name] [perm combo or silent label] [spacer]
            gbc.gridwidth = 1; gbc.gridx = 0
            content.add(row.enabledBox ?: JBPanel<JBPanel<*>>().apply {
                preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20)); isOpaque = false
            }, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            val badge = if (row.tool.isBuiltIn) " <small>[Built-in]</small>" else ""
            content.add(JBLabel("<html>${row.tool.displayName}$badge</html>").apply {
                if (row.tool.isBuiltIn) foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(4)
                if (row.tool.description.isNotEmpty()) toolTipText = "<html>${row.tool.description}</html>"
            }, gbc)

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            if (row.permCombo != null) {
                content.add(row.permCombo, gbc)
            } else {
                content.add(JBLabel("\uD83D\uDD12 runs silently").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(font.size2D - 1f)
                    toolTipText = SILENT_TOOLTIP
                }, gbc)
            }

            gbc.gridx = 3; content.add(JBPanel<JBPanel<*>>(), gbc)
            gbc.gridy++

            if (row.inProjectCombo != null && row.outProjectCombo != null) {
                addSubPermRow(content, gbc, "\u00a0\u00a0\u00a0\u25b8 Inside project:", row.inProjectCombo)
                addSubPermRow(content, gbc, "\u00a0\u00a0\u00a0\u25b8 Outside project:", row.outProjectCombo)
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

    // ── Persist ───────────────────────────────────────────────────────────────

    fun save() {
        for (row in rows) {
            val id = row.tool.id
            if (row.isPlugin) {
                row.enabledBox?.let { CopilotSettings.setToolEnabled(id, it.isSelected) }
                row.permCombo?.let { CopilotSettings.setToolPermission(id, it.selectedIndex.toPluginPermission()) }
                row.inProjectCombo?.let {
                    CopilotSettings.setToolPermissionInsideProject(id, it.selectedIndex.toPluginPermission())
                }
                row.outProjectCombo?.let {
                    CopilotSettings.setToolPermissionOutsideProject(id, it.selectedIndex.toPluginPermission())
                }
            } else {
                row.permCombo?.let { CopilotSettings.setToolPermission(id, it.selectedIndex.toBuiltinPermission()) }
            }
        }
    }
}
