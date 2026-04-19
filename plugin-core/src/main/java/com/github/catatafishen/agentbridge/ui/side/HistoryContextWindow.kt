package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.EntryData
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Non-modal floating window showing conversation history centered on a specific prompt.
 *
 * Opened when the user clicks a prompt in the Prompts tab that has not yet been rendered
 * in the main JCEF chat view (i.e., it is still in the deferred history queue). The initial
 * view shows one prompt before and one after the target. "Load more" controls at both edges
 * expand the window in either direction, 3 prompts at a time.
 */
internal class HistoryContextWindow private constructor(
    project: Project,
    allEntries: List<EntryData>,
    targetEntryId: String,
) : JDialog(WindowManager.getInstance().getFrame(project), "Conversation History", false) {

    companion object {
        /** Initial context prompts shown on each side of the target. */
        private const val INITIAL_CONTEXT = 1

        /** Number of additional prompts revealed per load-more click. */
        private const val LOAD_STEP = 3

        fun open(project: Project, allEntries: List<EntryData>, targetEntryId: String) {
            val win = HistoryContextWindow(project, allEntries, targetEntryId)
            win.pack()
            win.setLocationRelativeTo(WindowManager.getInstance().getFrame(project))
            win.isVisible = true
        }
    }

    private data class PromptItem(
        val prompt: EntryData.Prompt,
        val stats: EntryData.TurnStats?,
        val commits: List<String>,
    )

    private val allPrompts: List<EntryData.Prompt>
    private val turnDataMap: Map<String, Pair<EntryData.TurnStats?, List<String>>>
    private val targetIndex: Int

    /** How many prompts before the target are currently shown. */
    private var shownBefore = INITIAL_CONTEXT

    /** How many prompts after the target are currently shown. */
    private var shownAfter = INITIAL_CONTEXT

    private val listModel = DefaultListModel<PromptItem>()
    private val promptList = JBList(listModel)

    private val loadOlderLabel = JLabel("↑ Load earlier prompts").apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadOlder()
        })
    }

    private val loadNewerLabel = JLabel("↓ Load more recent prompts").apply {
        font = JBUI.Fonts.miniFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = loadNewer()
        })
    }

    private val loadOlderPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(loadOlderLabel, BorderLayout.CENTER)
        isVisible = false
    }

    private val loadNewerPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(loadNewerLabel, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        allPrompts = allEntries.filterIsInstance<EntryData.Prompt>()
        turnDataMap = buildTurnDataMap(allEntries)
        targetIndex = allPrompts.indexOfFirst { PromptsPanel.promptEntryId(it) == targetEntryId }
            .let { if (it < 0) allPrompts.size - 1 else it }

        promptList.cellRenderer = BubbleRenderer()
        promptList.fixedCellHeight = -1

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(loadOlderPanel, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.empty()
        centerPanel.add(scrollPane, BorderLayout.CENTER)
        centerPanel.add(loadNewerPanel, BorderLayout.SOUTH)
        contentPane.add(centerPanel, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(480), JBUI.scale(500))

        refresh()

        // Select and scroll to the target after the window is laid out
        SwingUtilities.invokeLater {
            val start = maxOf(0, targetIndex - shownBefore)
            val targetInModel = (targetIndex - start).coerceIn(0, listModel.size() - 1)
            promptList.selectedIndex = targetInModel
            val bounds = promptList.getCellBounds(targetInModel, targetInModel)
            if (bounds != null) promptList.scrollRectToVisible(bounds)
        }

        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true
    }

    private fun refresh() {
        val start = maxOf(0, targetIndex - shownBefore)
        val end = minOf(allPrompts.size - 1, targetIndex + shownAfter)

        loadOlderPanel.isVisible = start > 0
        loadNewerPanel.isVisible = end < allPrompts.size - 1

        listModel.clear()
        for (i in start..end) {
            val p = allPrompts[i]
            val key = PromptsPanel.promptEntryId(p)
            val (stats, commits) = turnDataMap[key] ?: Pair(null, emptyList<String>())
            listModel.addElement(PromptItem(p, stats, commits))
        }
    }

    private fun loadOlder() {
        val prevStart = maxOf(0, targetIndex - shownBefore)
        shownBefore += LOAD_STEP
        refresh()
        // Keep the previously-top item visible after new entries are prepended
        val newStart = maxOf(0, targetIndex - shownBefore)
        val offsetInModel = (prevStart - newStart).coerceAtLeast(0)
        if (offsetInModel < listModel.size()) {
            val bounds = promptList.getCellBounds(offsetInModel, offsetInModel)
            if (bounds != null) promptList.scrollRectToVisible(bounds)
        }
    }

    private fun loadNewer() {
        shownAfter += LOAD_STEP
        refresh()
        val lastIdx = listModel.size() - 1
        if (lastIdx >= 0) {
            val bounds = promptList.getCellBounds(lastIdx, lastIdx)
            if (bounds != null) promptList.scrollRectToVisible(bounds)
        }
    }

    private class BubbleRenderer : ListCellRenderer<PromptItem> {
        private val outer = JPanel(BorderLayout(0, JBUI.scale(2)))
        private val headerPanel = JPanel(BorderLayout())
        private val tsLabel = JLabel()
        private val statsLabel = JLabel()
        private val textArea = JTextArea()
        private val commitsLabel = JLabel()
        private val commitsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(commitsLabel, BorderLayout.CENTER)
        }

        init {
            outer.isOpaque = true
            tsLabel.font = JBUI.Fonts.miniFont()
            tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            statsLabel.font = JBUI.Fonts.miniFont()
            statsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            headerPanel.isOpaque = false
            headerPanel.add(tsLabel, BorderLayout.WEST)
            headerPanel.add(statsLabel, BorderLayout.EAST)
            textArea.isOpaque = false
            textArea.isEditable = false
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            textArea.font = UIManager.getFont("Label.font") ?: textArea.font
            textArea.border = null
            textArea.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            commitsLabel.font = JBUI.Fonts.miniFont()
            commitsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            outer.add(headerPanel, BorderLayout.NORTH)
            outer.add(textArea, BorderLayout.CENTER)
            outer.add(commitsPanel, BorderLayout.SOUTH)
            outer.border = JBUI.Borders.compound(
                JBUI.Borders.empty(1, 0),
                JBUI.Borders.empty(4, 8, 4, 6)
            )
        }

        override fun getListCellRendererComponent(
            list: JList<out PromptItem>?,
            value: PromptItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) return outer
            val listWidth = list?.width ?: 0
            if (listWidth > 0) {
                textArea.setSize(listWidth - JBUI.scale(18), Short.MAX_VALUE.toInt())
            }
            tsLabel.text = PromptsPanel.formatTimestamp(value.prompt.timestamp)
            statsLabel.text = PromptsPanel.formatStats(value.stats)
            textArea.text = PromptsPanel.truncatePrompt(value.prompt.text.trim())

            val commitsText = PromptsPanel.formatCommits(value.commits)
            commitsLabel.text = commitsText
            commitsPanel.isVisible = commitsText.isNotEmpty()

            if (isSelected) {
                val selFg = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                outer.background = list?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
                textArea.foreground = selFg
                tsLabel.foreground = selFg
                statsLabel.foreground = selFg
                commitsLabel.foreground = selFg
            } else {
                outer.background = list?.background ?: UIManager.getColor("List.background")
                textArea.foreground = list?.foreground ?: UIManager.getColor("List.foreground")
                tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                statsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                commitsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }

            val commitsHeight = if (commitsPanel.isVisible) commitsLabel.preferredSize.height + JBUI.scale(2) else 0
            val textHeight = textArea.preferredSize.height
            outer.preferredSize = Dimension(
                listWidth,
                tsLabel.preferredSize.height + JBUI.scale(2) + textHeight + commitsHeight + JBUI.scale(10)
            )
            return outer
        }
    }
}

private fun buildTurnDataMap(entries: List<EntryData>): Map<String, Pair<EntryData.TurnStats?, List<String>>> {
    val result = mutableMapOf<String, Pair<EntryData.TurnStats?, List<String>>>()
    var lastPromptId: String? = null
    for (entry in entries) {
        when (entry) {
            is EntryData.Prompt -> lastPromptId = PromptsPanel.promptEntryId(entry)
            is EntryData.TurnStats -> {
                val key = entry.turnId.takeIf { it.isNotEmpty() } ?: lastPromptId
                if (key != null) {
                    result[key] = Pair(entry, entry.commitHashes)
                    lastPromptId = null
                }
            }

            else -> Unit
        }
    }
    return result
}
