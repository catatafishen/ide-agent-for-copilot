package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.ui.ChatConsolePanel
import com.github.catatafishen.agentbridge.ui.EntryData
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent

internal class PromptsPanel(
    @Suppress("UNUSED_PARAMETER") project: Project,
    private val chatConsole: ChatConsolePanel
) : JPanel(BorderLayout()), Disposable {

    private data class PromptItem(val prompt: EntryData.Prompt, val stats: EntryData.TurnStats?)

    private val searchField = SearchTextField()
    private val listModel = DefaultListModel<PromptItem>()
    private val promptList = JBList(listModel)
    private val entriesListener = Runnable {
        ApplicationManager.getApplication().invokeLater(::refresh)
    }

    init {
        promptList.cellRenderer = BubbleRenderer()
        promptList.fixedCellHeight = -1
        promptList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = promptList.locationToIndex(e.point)
                if (idx < 0) return
                val item = listModel.getElementAt(idx) ?: return
                val entryId = promptEntryId(item.prompt)
                if (entryId.isNotEmpty()) chatConsole.scrollToEntry(entryId)
            }
        })

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        searchField.textEditor.emptyText.text = "Search prompts…"

        val top = JPanel(BorderLayout())
        top.border = JBUI.Borders.empty(4)
        top.add(searchField, BorderLayout.CENTER)

        add(top, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(promptList)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)

        chatConsole.addEntriesChangeListener(entriesListener)
        refresh()
    }

    private fun refresh() {
        val query = searchField.text.orEmpty()
        val allEntries = chatConsole.entriesSnapshot()
        val prompts = allEntries.filterIsInstance<EntryData.Prompt>()
        val filtered = filterPrompts(prompts, query)
        val statsMap = buildStatsMap(allEntries)
        listModel.clear()
        filtered.forEach { p -> listModel.addElement(PromptItem(p, statsMap[promptEntryId(p)])) }
    }

    override fun dispose() {
        chatConsole.removeEntriesChangeListener(entriesListener)
    }

    private class BubbleRenderer : ListCellRenderer<PromptItem> {
        private val outer = JPanel(BorderLayout(0, JBUI.scale(2)))
        private val headerPanel = JPanel(BorderLayout())
        private val tsLabel = javax.swing.JLabel()
        private val statsLabel = javax.swing.JLabel()
        private val textArea = JTextArea()

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
            textArea.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            outer.add(headerPanel, BorderLayout.NORTH)
            outer.add(textArea, BorderLayout.CENTER)
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
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return outer
            val listWidth = list?.width ?: 0
            if (listWidth > 0) {
                textArea.setSize(listWidth - JBUI.scale(18), Short.MAX_VALUE.toInt())
            }
            tsLabel.text = formatTimestamp(value.prompt.timestamp)
            statsLabel.text = formatStats(value.stats)
            textArea.text = value.prompt.text.trim()
            if (isSelected) {
                outer.background = list?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
                textArea.foreground = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                tsLabel.foreground = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                statsLabel.foreground = list?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
            } else {
                outer.background = list?.background ?: UIManager.getColor("List.background")
                textArea.foreground = list?.foreground ?: UIManager.getColor("List.foreground")
                tsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                statsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            val textHeight = textArea.preferredSize.height
            outer.preferredSize = Dimension(
                listWidth,
                tsLabel.preferredSize.height + JBUI.scale(2) + textHeight + JBUI.scale(10)
            )
            return outer
        }
    }

    companion object {
        fun formatTimestamp(iso: String): String {
            if (iso.isEmpty()) return ""
            return try {
                val instant = java.time.Instant.parse(iso)
                val zdt = instant.atZone(ZoneId.systemDefault())
                val today = LocalDate.now()
                val date = zdt.toLocalDate()
                val time = DateTimeFormatter.ofPattern("HH:mm").format(zdt)
                when {
                    date == today -> "Today $time"
                    date == today.minusDays(1) -> "Yesterday $time"
                    date.year == today.year -> "${DateTimeFormatter.ofPattern("MMM d").format(zdt)} $time"
                    else -> "${DateTimeFormatter.ofPattern("MMM d yyyy").format(zdt)} $time"
                }
            } catch (_: Exception) {
                iso
            }
        }

        fun filterPrompts(prompts: List<EntryData.Prompt>, query: String): List<EntryData.Prompt> {
            val q = query.trim()
            if (q.isEmpty()) return prompts
            val needle = q.lowercase()
            return prompts.filter { it.text.lowercase().contains(needle) }
        }

        fun promptEntryId(p: EntryData.Prompt): String = p.id.ifEmpty { p.entryId }

        fun buildStatsMap(entries: List<EntryData>): Map<String, EntryData.TurnStats> {
            val result = mutableMapOf<String, EntryData.TurnStats>()
            var lastPromptId: String? = null
            for (entry in entries) {
                when (entry) {
                    is EntryData.Prompt -> lastPromptId = promptEntryId(entry)
                    is EntryData.TurnStats -> {
                        val pid = lastPromptId
                        if (pid != null) {
                            result[pid] = entry
                            lastPromptId = null
                        }
                    }

                    else -> Unit
                }
            }
            return result
        }

        fun formatStats(stats: EntryData.TurnStats?): String {
            if (stats == null) return ""
            val parts = mutableListOf<String>()
            if (stats.toolCallCount > 0) parts.add("${stats.toolCallCount} tools")
            if (stats.durationMs > 0) {
                val s = stats.durationMs / 1000.0
                parts.add(if (s < 60) "%.1fs".format(s) else "${(s / 60).toInt()}m ${(s % 60).toInt()}s")
            }
            return parts.joinToString(" · ")
        }
    }
}
