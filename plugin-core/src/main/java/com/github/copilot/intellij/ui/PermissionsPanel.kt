package com.github.copilot.intellij.ui

import com.github.copilot.intellij.services.CopilotSettings
import com.github.copilot.intellij.services.ToolPermission
import com.github.copilot.intellij.services.ToolRegistry
import com.github.copilot.intellij.services.ToolRegistry.Category
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.tree.*

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
    "<html>This tool runs without a permission request — no control available</html>"
private const val RESTART_NOTE =
    "<html><i>⚠ Changes to enabled/disabled take effect after restarting the agent session.</i></html>"

/** Navigation node user-object for the tree. */
private sealed class NavNode(val label: String) {
    class Section(val isBuiltIn: Boolean) :
        NavNode(if (isBuiltIn) "Built-in Tools" else "IntelliJ Plugin Tools")

    class Cat(val category: Category, val isBuiltIn: Boolean) : NavNode(category.displayName)

    override fun toString() = label
}

/** Cell renderer that renders section nodes bold and category nodes normally, with icons. */
private class NavTreeCellRenderer : TreeCellRenderer {
    private val label = SimpleColoredComponent()

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        label.clear()
        label.font = UIUtil.getTreeFont()
        label.border = JBUI.Borders.empty(2, 4, 2, 4)
        label.background = if (selected) UIUtil.getTreeSelectionBackground(hasFocus) else UIUtil.SIDE_PANEL_BACKGROUND
        label.foreground = if (selected) UIUtil.getTreeSelectionForeground(hasFocus) else UIUtil.getTreeForeground()
        label.isOpaque = selected

        val node = value as? DefaultMutableTreeNode
        when (val nav = node?.userObject) {
            is NavNode.Section -> {
                label.icon = if (nav.isBuiltIn) AllIcons.Nodes.Plugin else AllIcons.Nodes.Module
                label.append(nav.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is NavNode.Cat -> {
                label.icon = AllIcons.Nodes.Folder
                label.append(nav.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            else -> label.append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        return label
    }
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
        val enabledBox: JBCheckBox?,
        val permCombo: ComboBox<String>?,
        val inProjectCombo: ComboBox<String>?,
        val outProjectCombo: ComboBox<String>?,
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
            val enabledBox: JBCheckBox?
            val permCombo: ComboBox<String>?

            when {
                !tool.isBuiltIn -> {
                    val enabled = CopilotSettings.isToolEnabled(tool.id)
                    enabledBox = JBCheckBox().apply {
                        isSelected = enabled
                        toolTipText = "Enable or disable this tool (requires agent restart)"
                    }
                    permCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                        setMinimumAndPreferredWidth(JBUI.scale(108))
                        isEnabled = enabled
                        selectedIndex = CopilotSettings.getToolPermission(tool.id).toPluginIndex()
                        toolTipText = "Permission when agent requests this tool"
                    }
                    enabledBox.addActionListener { permCombo.isEnabled = enabledBox.isSelected }
                }

                tool.hasDenyControl -> {
                    enabledBox = null
                    permCombo = ComboBox(BUILTIN_PERM_OPTIONS).apply {
                        setMinimumAndPreferredWidth(JBUI.scale(108))
                        selectedIndex = CopilotSettings.getToolPermission(tool.id).toBuiltinIndex()
                        toolTipText = BUILTIN_TOOLTIP
                    }
                }

                else -> {
                    enabledBox = null
                    permCombo = null
                }
            }

            val inProjectCombo: ComboBox<String>?
            val outProjectCombo: ComboBox<String>?
            if (tool.supportsPathSubPermissions && !tool.isBuiltIn && enabledBox != null) {
                val topIsAllow = permCombo?.selectedIndex == 0
                val toolEnabled = enabledBox.isSelected

                fun subTooltip(inside: Boolean, active: Boolean) =
                    if (active) "Permission for files ${if (inside) "inside" else "outside"} the current project"
                    else "Controlled by the top-level permission above"

                inProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                    setMinimumAndPreferredWidth(JBUI.scale(108))
                    isEnabled = toolEnabled && topIsAllow
                    selectedIndex = CopilotSettings.getToolPermissionInsideProject(tool.id).toPluginIndex()
                    toolTipText = subTooltip(true, toolEnabled && topIsAllow)
                }
                outProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                    setMinimumAndPreferredWidth(JBUI.scale(108))
                    isEnabled = toolEnabled && topIsAllow
                    selectedIndex = CopilotSettings.getToolPermissionOutsideProject(tool.id).toPluginIndex()
                    toolTipText = subTooltip(false, toolEnabled && topIsAllow)
                }

                fun updateSubCombos() {
                    val enabled = enabledBox.isSelected
                    val allow = permCombo?.selectedIndex == 0
                    val active = enabled && allow
                    inProjectCombo.isEnabled = active
                    outProjectCombo.isEnabled = active
                    inProjectCombo.toolTipText = subTooltip(true, active)
                    outProjectCombo.toolTipText = subTooltip(false, active)
                }

                enabledBox.addActionListener { updateSubCombos() }
                permCombo?.addActionListener { updateSubCombos() }
            } else {
                inProjectCombo = null
                outProjectCombo = null
            }

            rows.add(ToolRow(tool, !tool.isBuiltIn, enabledBox, permCombo, inProjectCombo, outProjectCombo))
        }
    }

    // ── Main split layout ─────────────────────────────────────────────────────

    private fun buildMainComponent(): JComponent {
        val root = DefaultMutableTreeNode("root")

        // Built-in section
        val builtinRoot = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = true))
        val builtinCats = rows.filter { it.tool.isBuiltIn }.map { it.tool.category }.distinct()
        for (cat in builtinCats) builtinRoot.add(DefaultMutableTreeNode(NavNode.Cat(cat, isBuiltIn = true)))
        root.add(builtinRoot)

        // IntelliJ plugin tools section
        val pluginRoot = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = false))
        val pluginCats = rows.filter { !it.tool.isBuiltIn }.map { it.tool.category }.distinct()
        for (cat in pluginCats) pluginRoot.add(DefaultMutableTreeNode(NavNode.Cat(cat, isBuiltIn = false)))
        root.add(pluginRoot)

        val tree = JTree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.border = JBUI.Borders.emptyLeft(4)
        tree.cellRenderer = NavTreeCellRenderer()
        tree.background = UIUtil.SIDE_PANEL_BACKGROUND
        tree.expandPath(TreePath(arrayOf(root, builtinRoot)))
        tree.expandPath(TreePath(arrayOf(root, pluginRoot)))

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Initial selection → plugin section
        val pluginPath = TreePath(arrayOf(root, pluginRoot))
        tree.selectionPath = pluginPath
        showTools(showRestartNote = true) { !it.tool.isBuiltIn }

        tree.addTreeSelectionListener { e ->
            val node = e?.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            when (val nav = node.userObject) {
                is NavNode.Section -> showTools(showRestartNote = !nav.isBuiltIn) { it.tool.isBuiltIn == nav.isBuiltIn }
                is NavNode.Cat -> showTools(showRestartNote = !nav.isBuiltIn) {
                    it.tool.category == nav.category && it.tool.isBuiltIn == nav.isBuiltIn
                }

                else -> {}
            }
        }

        val treeScroll = JBScrollPane(tree)
        treeScroll.border = JBUI.Borders.empty()
        treeScroll.preferredSize = Dimension(JBUI.scale(200), 0)
        treeScroll.minimumSize = Dimension(JBUI.scale(150), 0)
        treeScroll.viewport.background = UIUtil.SIDE_PANEL_BACKGROUND

        return OnePixelSplitter(false, "CopilotPermissionsPanel.splitter", 0.28f).also { splitter ->
            splitter.firstComponent = treeScroll
            splitter.secondComponent = rightContent
        }
    }

    // ── Right-panel rendering ─────────────────────────────────────────────────

    private fun showTools(showRestartNote: Boolean, filter: (ToolRow) -> Boolean) {
        val filtered = rows.filter(filter)
        rightContent.removeAll()
        if (filtered.isEmpty()) {
            val empty = JBLabel("No tools in this category.", SwingConstants.CENTER)
            empty.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            rightContent.add(empty, BorderLayout.CENTER)
        } else {
            rightContent.add(buildContentPanel(filtered, showRestartNote), BorderLayout.CENTER)
        }
        rightContent.revalidate()
        rightContent.repaint()
    }

    private fun buildContentPanel(filtered: List<ToolRow>, showRestartNote: Boolean): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(10, 16, 12, 16)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        if (showRestartNote) {
            gbc.gridwidth = 3; gbc.weightx = 1.0
            content.add(JBLabel(RESTART_NOTE).apply {
                foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                border = JBUI.Borders.empty(0, 0, 6, 0)
            }, gbc)
            gbc.gridy++
            content.add(
                SeparatorComponent(0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), null),
                gbc
            )
            gbc.gridy++
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
        }

        var lastCategory: Category? = null
        for (row in filtered) {
            if (row.tool.category != lastCategory) {
                lastCategory = row.tool.category
                gbc.gridx = 0; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                gbc.insets = JBUI.insets(12, 0, 4, 0)
                content.add(TitledSeparator(row.tool.category.displayName), gbc)
                gbc.gridy++
                gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.insets(2, 0)
            }

            // [checkbox or spacer] [name] [perm combo or silent label]
            gbc.gridwidth = 1; gbc.gridx = 0
            content.add(row.enabledBox ?: JBPanel<JBPanel<*>>().apply {
                preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
                isOpaque = false
            }, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            val nameLabel = SimpleColoredComponent().apply {
                append(row.tool.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (row.tool.description.isNotEmpty()) toolTipText = row.tool.description
                border = JBUI.Borders.emptyLeft(4)
            }
            content.add(nameLabel, gbc)

            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            if (row.permCombo != null) {
                content.add(row.permCombo, gbc)
            } else {
                content.add(JBLabel("Runs silently", AllIcons.Actions.Suspend, SwingConstants.LEFT).apply {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    font = JBUI.Fonts.smallFont()
                    toolTipText = SILENT_TOOLTIP
                }, gbc)
            }

            gbc.gridy++

            if (row.inProjectCombo != null && row.outProjectCombo != null) {
                addSubPermRow(content, gbc, "   ▸ Inside project:", row.inProjectCombo)
                addSubPermRow(content, gbc, "   ▸ Outside project:", row.outProjectCombo)
            }
        }

        // Bottom spacer
        gbc.gridwidth = 3; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0
        content.add(JBPanel<JBPanel<*>>(), gbc)

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun addSubPermRow(
        panel: JBPanel<*>, gbc: GridBagConstraints,
        labelText: String, combo: ComboBox<String>
    ) {
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridx = 1
        gbc.insets = JBUI.insets(0, 24, 0, 0)
        panel.add(JBLabel(labelText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
        }, gbc)
        gbc.gridx = 2; gbc.insets = JBUI.insets(1, 0)
        panel.add(combo, gbc)
        gbc.gridy++
        gbc.insets = JBUI.insets(2, 0)
    }

    // ── Persist ───────────────────────────────────────────────────────────────

    fun save() {
        for (row in rows) {
            val id = row.tool.id
            if (row.isPlugin) {
                row.enabledBox?.let { CopilotSettings.setToolEnabled(id, it.isSelected) }
                row.permCombo?.let { combo ->
                    val perm = combo.selectedIndex.toPluginPermission()
                    CopilotSettings.setToolPermission(id, perm)

                    // Only persist sub-permissions when top-level is Allow (the ceiling).
                    // When top-level is Ask, clear stored sub-keys so they don't silently override.
                    if (perm == ToolPermission.ALLOW) {
                        row.inProjectCombo?.let {
                            CopilotSettings.setToolPermissionInsideProject(id, it.selectedIndex.toPluginPermission())
                        }
                        row.outProjectCombo?.let {
                            CopilotSettings.setToolPermissionOutsideProject(id, it.selectedIndex.toPluginPermission())
                        }
                    } else {
                        CopilotSettings.clearToolSubPermissions(id)
                    }
                }
            } else {
                row.permCombo?.let { CopilotSettings.setToolPermission(id, it.selectedIndex.toBuiltinPermission()) }
            }
        }
    }
}
