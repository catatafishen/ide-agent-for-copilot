package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.services.AgentUiSettings
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.Category
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.tree.*

// Permission options
private val PLUGIN_PERM_OPTIONS = arrayOf("Allow", "Ask")

private fun ToolPermission.toPluginIndex() = when (this) {
    ToolPermission.ASK -> 1; else -> 0
}

private fun Int.toPluginPermission() = if (this == 1) ToolPermission.ASK else ToolPermission.ALLOW

private const val SILENT_TOOLTIP =
    "<html>This tool runs without a permission request — no control available</html>"

/**
 * Permission batch groups, matching the tool-chip CSS color palette.
 *
 * CSS source (chat.css):
 *   --kind-read:    rgb(100, 185, 185)
 *   --kind-edit:    rgb(205, 155,  95)
 *   --kind-execute: rgb(130, 190, 130)
 */
private enum class KindGroup(
    val label: String,
    val description: String,
    private val lightColor: Color,
    private val darkColor: Color,
) {
    READ(
        "Read & Navigate",
        "File reads, search, git log/diff, code quality checks",
        Color(0, 130, 130), Color(100, 185, 185)
    ),
    EDIT(
        "Edit & Refactor",
        "File writes, git stage/commit/merge, refactoring",
        Color(150, 90, 20), Color(205, 155, 95)
    ),
    EXECUTE(
        "Run & Execute",
        "Shell commands, run configs, git push/reset, delete files",
        Color(30, 120, 30), Color(130, 190, 130)
    );

    val color: Color get() = JBColor(lightColor, darkColor)
}

/** Maps a [ToolDefinition] to its batch [KindGroup], or null for unclassified tools. */
private fun ToolDefinition.kindGroup(): KindGroup? = when {
    isReadOnly -> KindGroup.READ
    kind() == "edit" -> KindGroup.EDIT
    kind() == "execute" -> KindGroup.EXECUTE
    else -> null
}

/** Navigation node user-object for the tree. */
private sealed class NavNode(val label: String) {
    class Section(val isBuiltIn: Boolean) :
        NavNode(if (isBuiltIn) "Built-in Tools" else "Plugin Tools")

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
 * right = scrollable grid for the selected set of tools, with Quick Permissions
 *         group controls at the top.
 *
 * This panel is purely about permission levels (allow/ask/deny).
 * Tool enable/disable is managed in Tool Registration settings.
 */
internal class PermissionsPanel(private val settings: AgentUiSettings, private val registry: ToolRegistry) {

    private data class ToolRow(
        val tool: ToolDefinition,
        val permCombo: ComboBox<String>?,
        val inProjectCombo: ComboBox<String>?,
        val outProjectCombo: ComboBox<String>?,
    )

    private val rows = mutableListOf<ToolRow>()

    /** Maps each KindGroup to its batch combo; populated in [buildGroupControlsPanel]. */
    private val groupCombos = mutableMapOf<KindGroup, ComboBox<String>>()

    /** Panel holding the Quick Permissions section; rebuilt when [reload] is called. */
    private var groupControlsPanel: JBPanel<*> = JBPanel<JBPanel<*>>()

    private val rightContent = JBPanel<JBPanel<*>>(BorderLayout())
    val component: JComponent

    init {
        buildAllRows()
        groupControlsPanel = buildGroupControlsPanel()
        component = buildMainComponent()
    }

    // ── Row construction ──────────────────────────────────────────────────────

    private fun buildAllRows() {
        for (tool in registry.allTools) {
            if (tool.isBuiltIn) continue

            val permCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                setMinimumAndPreferredWidth(JBUI.scale(108))
                selectedIndex = settings.getToolPermission(tool.id()).toPluginIndex()
                toolTipText = "Permission when agent requests this tool"
            }

            val (inProjectCombo, outProjectCombo) = buildSubPermCombos(tool, permCombo)
            rows.add(ToolRow(tool, permCombo, inProjectCombo, outProjectCombo))
        }
    }

    private fun buildSubPermCombos(
        tool: ToolDefinition,
        permCombo: ComboBox<String>?
    ): Pair<ComboBox<String>?, ComboBox<String>?> {
        if (!tool.supportsPathSubPermissions() || tool.isBuiltIn || permCombo == null) {
            return Pair(null, null)
        }

        val topIsAllow = permCombo.selectedIndex == 0

        fun subTooltip(inside: Boolean, active: Boolean) =
            if (active) "Permission for files ${if (inside) "inside" else "outside"} the current project"
            else "Controlled by the top-level permission above"

        val inProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
            setMinimumAndPreferredWidth(JBUI.scale(108))
            isEnabled = topIsAllow
            selectedIndex = settings.getToolPermissionInsideProject(tool.id()).toPluginIndex()
            toolTipText = subTooltip(true, topIsAllow)
        }
        val outProjectCombo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
            setMinimumAndPreferredWidth(JBUI.scale(108))
            isEnabled = topIsAllow
            selectedIndex = settings.getToolPermissionOutsideProject(tool.id()).toPluginIndex()
            toolTipText = subTooltip(false, topIsAllow)
        }

        permCombo.addActionListener {
            val allow = permCombo.selectedIndex == 0
            inProjectCombo.isEnabled = allow
            outProjectCombo.isEnabled = allow
            inProjectCombo.toolTipText = subTooltip(true, allow)
            outProjectCombo.toolTipText = subTooltip(false, allow)
        }

        return Pair(inProjectCombo, outProjectCombo)
    }

    // ── Group controls panel ──────────────────────────────────────────────────

    /**
     * Builds the "Quick Permissions" panel shown at the top of the right panel.
     * Each [KindGroup] gets a colored label and a batch Allow/Ask combo that
     * propagates to all matching tool rows when changed.
     */
    private fun buildGroupControlsPanel(): JBPanel<*> {
        groupCombos.clear()
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(10, 16, 8, 16)

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(2, 0)
            gridx = 0; gridy = 0
        }

        // Section header
        gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(TitledSeparator("Quick Permissions"), gbc)
        gbc.gridy++
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 0, 2, 0)

        for (group in KindGroup.entries) {
            // Colored "●" dot
            gbc.gridx = 0
            panel.add(JBLabel("● ").apply {
                foreground = group.color
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size + 2f)
                toolTipText = group.description
            }, gbc)

            // Group name
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(JBLabel(group.label).apply {
                toolTipText = group.description
            }, gbc)

            // Batch combo
            gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            val combo = ComboBox(PLUGIN_PERM_OPTIONS).apply {
                setMinimumAndPreferredWidth(JBUI.scale(108))
                selectedIndex = computeGroupInitialIndex(group)
                toolTipText = "<html>Set <b>${group.label}</b> permission for all tools in this group</html>"
            }
            groupCombos[group] = combo
            combo.addActionListener { applyGroupPermission(group) }
            panel.add(combo, gbc)

            gbc.gridy++
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        }

        return panel
    }

    /**
     * Computes the initial combo index for a [KindGroup]:
     * if any tool in the group is set to Ask, shows Ask; otherwise shows Allow.
     */
    private fun computeGroupInitialIndex(group: KindGroup): Int {
        val groupRows = rows.filter { it.tool.kindGroup() == group && it.permCombo != null }
        return if (groupRows.any { it.permCombo!!.selectedIndex == 1 }) 1 else 0
    }

    /** Propagates the group combo selection to all individual tool combos in that group. */
    private fun applyGroupPermission(group: KindGroup) {
        val idx = groupCombos[group]?.selectedIndex ?: return
        rows.filter { it.tool.kindGroup() == group }.forEach { row ->
            row.permCombo?.selectedIndex = idx
        }
    }

    // ── Main split layout ─────────────────────────────────────────────────────

    private fun buildMainComponent(): JComponent {
        val root = DefaultMutableTreeNode("root")

        // IntelliJ plugin tools section
        val pluginRoot = DefaultMutableTreeNode(NavNode.Section(isBuiltIn = false))
        val pluginCats = rows.filter { !it.tool.isBuiltIn }.map { it.tool.category() }.distinct()
        for (cat in pluginCats) pluginRoot.add(DefaultMutableTreeNode(NavNode.Cat(cat, isBuiltIn = false)))
        root.add(pluginRoot)

        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.border = JBUI.Borders.emptyLeft(4)
        tree.cellRenderer = NavTreeCellRenderer()
        tree.background = UIUtil.SIDE_PANEL_BACKGROUND
        tree.expandPath(TreePath(arrayOf(root, pluginRoot)))

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Initial selection → plugin section
        val pluginPath = TreePath(arrayOf(root, pluginRoot))
        tree.selectionPath = pluginPath
        showTools { !it.tool.isBuiltIn }

        tree.addTreeSelectionListener { e ->
            val node = e?.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            when (val nav = node.userObject) {
                is NavNode.Section -> showTools { it.tool.isBuiltIn == nav.isBuiltIn }
                is NavNode.Cat -> showTools {
                    it.tool.category() == nav.category && it.tool.isBuiltIn == nav.isBuiltIn
                }

                else -> Unit // other node types — no action needed
            }
        }

        val treeScroll = JBScrollPane(tree)
        treeScroll.border = JBUI.Borders.empty()
        treeScroll.preferredSize = JBUI.size(200, 0)
        treeScroll.minimumSize = JBUI.size(150, 0)
        treeScroll.viewport.background = UIUtil.SIDE_PANEL_BACKGROUND

        return OnePixelSplitter(false, "CopilotPermissionsPanel.splitter", 0.28f).also { splitter ->
            splitter.firstComponent = treeScroll
            splitter.secondComponent = rightContent
        }
    }

    // ── Right-panel rendering ─────────────────────────────────────────────────

    private fun showTools(filter: (ToolRow) -> Boolean) {
        val filtered = rows.filter(filter)
        rightContent.removeAll()
        rightContent.add(groupControlsPanel, BorderLayout.NORTH)
        if (filtered.isEmpty()) {
            val empty = JBLabel("No tools in this category.", SwingConstants.CENTER)
            empty.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            rightContent.add(empty, BorderLayout.CENTER)
        } else {
            rightContent.add(buildContentPanel(filtered), BorderLayout.CENTER)
        }
        rightContent.revalidate()
        rightContent.repaint()
    }

    private fun buildContentPanel(filtered: List<ToolRow>): JComponent {
        val content = JBPanel<JBPanel<*>>(GridBagLayout())
        content.border = JBUI.Borders.empty(10, 16, 12, 16)

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(1, 0)
        }

        var lastCategory: Category? = null
        for (row in filtered) {
            if (row.tool.category() != lastCategory) {
                lastCategory = row.tool.category()
                addCategoryHeader(content, gbc, row.tool.category())
            }
            addToolRow(content, gbc, row)
            gbc.gridy++
            if (row.inProjectCombo != null && row.outProjectCombo != null) {
                addSubPermRow(content, gbc, "▸ Inside project:", row.inProjectCombo)
                addSubPermRow(content, gbc, "▸ Outside project:", row.outProjectCombo)
            }
        }

        // Bottom spacer
        gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0
        content.add(JBPanel<JBPanel<*>>(), gbc)

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun addCategoryHeader(content: JBPanel<*>, gbc: GridBagConstraints, category: Category) {
        gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(12, 0, 4, 0)
        content.add(TitledSeparator(category.displayName), gbc)
        gbc.gridy++
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.insets = JBUI.insets(2, 0)
    }

    private fun addToolRow(content: JBPanel<*>, gbc: GridBagConstraints, row: ToolRow) {
        gbc.gridwidth = 1; gbc.gridx = 0
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        val nameLabel = SimpleColoredComponent().apply {
            append(row.tool.displayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (row.tool.description().isNotEmpty()) toolTipText = row.tool.description()
            border = JBUI.Borders.empty(4, 4, 4, 8)
        }
        content.add(nameLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        if (row.permCombo != null) {
            val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
            wrapper.add(row.permCombo, BorderLayout.WEST)
            // Show "(custom)" indicator if tool perm differs from its group's batch setting
            val group = row.tool.kindGroup()
            val groupIdx = group?.let { groupCombos[it]?.selectedIndex }
            if (groupIdx != null && row.permCombo.selectedIndex != groupIdx) {
                wrapper.add(JBLabel(" (custom)").apply {
                    font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    toolTipText = "This tool's permission differs from the '${group.label}' group default"
                }, BorderLayout.CENTER)
            }
            content.add(wrapper, gbc)
        } else {
            content.add(JBLabel("Runs silently", AllIcons.Actions.Suspend, SwingConstants.LEFT).apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                font = JBUI.Fonts.smallFont()
                toolTipText = SILENT_TOOLTIP
            }, gbc)
        }
    }

    private fun addSubPermRow(
        panel: JBPanel<*>, gbc: GridBagConstraints,
        labelText: String, combo: ComboBox<String>
    ) {
        gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridx = 0
        gbc.insets = JBUI.insets(0, 24, 0, 8)
        panel.add(JBLabel(labelText).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = JBUI.insets(1, 0)
        val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
        wrapper.add(combo, BorderLayout.WEST)
        panel.add(wrapper, gbc)
        gbc.gridy++
        gbc.insets = JBUI.insets(2, 0)
    }

    // ── Persist ───────────────────────────────────────────────────────────────

    fun isModified(): Boolean {
        for (row in rows) {
            val id = row.tool.id()
            val combo = row.permCombo ?: return false
            if (combo.selectedIndex.toPluginPermission() != settings.getToolPermission(id)) return true
            if (combo.selectedIndex == 0) {
                row.inProjectCombo?.let {
                    if (it.selectedIndex.toPluginPermission() != settings.getToolPermissionInsideProject(id)) return true
                }
                row.outProjectCombo?.let {
                    if (it.selectedIndex.toPluginPermission() != settings.getToolPermissionOutsideProject(id)) return true
                }
            }
        }
        return false
    }

    /** Rebuilds the panel from persisted settings. */
    fun reload() {
        rows.clear()
        buildAllRows()
        groupControlsPanel = buildGroupControlsPanel()
        rightContent.removeAll()
        rightContent.revalidate()
        rightContent.repaint()
    }

    fun save() {
        for (row in rows) {
            val id = row.tool.id()
            row.permCombo?.let { combo ->
                val perm = combo.selectedIndex.toPluginPermission()
                settings.setToolPermission(id, perm)

                if (perm == ToolPermission.ALLOW) {
                    row.inProjectCombo?.let {
                        settings.setToolPermissionInsideProject(id, it.selectedIndex.toPluginPermission())
                    }
                    row.outProjectCombo?.let {
                        settings.setToolPermissionOutsideProject(id, it.selectedIndex.toPluginPermission())
                    }
                } else {
                    settings.clearToolSubPermissions(id)
                }
            }
        }
    }
}
