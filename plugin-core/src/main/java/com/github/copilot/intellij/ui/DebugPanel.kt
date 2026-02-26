package com.github.copilot.intellij.ui

import com.github.copilot.intellij.bridge.CopilotAcpClient
import com.github.copilot.intellij.services.CopilotService
import com.github.copilot.intellij.services.CopilotSettings
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Debug, timeline, log viewer, and settings tabs.
 * Extracted from AgenticCopilotToolWindowContent for file-size reduction.
 */
internal class DebugPanel(
    private val project: Project,
    private val timelineModel: DefaultListModel<TimelineEvent>
) {
    companion object {
        private const val AGENT_WORK_DIR = ".agent-work"
        private val ERROR_COLOR: JBColor
            get() = UIManager.getColor("Label.errorForeground") as? JBColor
                ?: JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x60, 0x60))
    }

    fun createTimelineTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val timelineList = JBList(timelineModel)
        timelineList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val event = value as? TimelineEvent
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                if (event != null) {
                    val icon = when (event.type) {
                        EventType.SESSION_START -> com.intellij.icons.AllIcons.Actions.Execute
                        EventType.MESSAGE_SENT -> com.intellij.icons.AllIcons.Actions.Upload
                        EventType.RESPONSE_RECEIVED -> com.intellij.icons.AllIcons.Actions.Download
                        EventType.ERROR -> com.intellij.icons.AllIcons.General.Error
                        EventType.TOOL_CALL -> com.intellij.icons.AllIcons.Actions.Lightning
                    }
                    label.icon = icon
                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss").format(event.timestamp)
                    label.text = "<html><b>$timeStr</b> - ${event.message}</html>"
                    label.border = JBUI.Borders.empty(5)
                }
                return label
            }
        }

        val scrollPane = JBScrollPane(timelineList)
        panel.add(scrollPane, BorderLayout.CENTER)

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(5)))
        val clearButton = JButton("Clear Timeline")
        clearButton.addActionListener {
            if (timelineModel.size() > 0) {
                timelineModel.clear()
            }
        }
        toolbar.add(clearButton)
        panel.add(toolbar, BorderLayout.SOUTH)

        return panel
    }

    fun createDebugTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        val buildInfoLabel = JBLabel("Build: ${com.github.copilot.intellij.BuildInfo.getSummary()}")
        buildInfoLabel.font = JBUI.Fonts.smallFont()
        buildInfoLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        panel.add(buildInfoLabel, BorderLayout.NORTH)

        val debugModel = DefaultListModel<CopilotAcpClient.DebugEvent>()
        val list = JBList(debugModel)
        list.cellRenderer = createDebugCellRenderer()

        val detailsArea = JBTextArea()
        detailsArea.isEditable = false
        detailsArea.lineWrap = true
        detailsArea.wrapStyleWord = true
        detailsArea.text = "Select an event to see details"

        setupDebugSelectionListener(list, detailsArea)

        val splitPane = OnePixelSplitter(true, 0.6f)
        splitPane.firstComponent = JBScrollPane(list)
        splitPane.secondComponent = JBScrollPane(detailsArea)
        panel.add(splitPane, BorderLayout.CENTER)

        panel.add(createDebugToolbar(debugModel, list), BorderLayout.SOUTH)
        registerDebugListener(debugModel, list)

        return panel
    }

    private fun createDebugCellRenderer(): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is CopilotAcpClient.DebugEvent) {
                    text = value.toString()
                    foreground = when (value.type) {
                        "PERMISSION_APPROVED" -> JBColor.GREEN.darker()
                        "PERMISSION_DENIED" -> ERROR_COLOR
                        "RETRY_PROMPT", "RETRY_RESPONSE" -> JBColor.ORANGE
                        else -> if (isSelected) list?.selectionForeground else list?.foreground
                    }
                }
                return this
            }
        }
    }

    private fun setupDebugSelectionListener(list: JBList<CopilotAcpClient.DebugEvent>, detailsArea: JBTextArea) {
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = list.selectedValue
                if (selected != null) {
                    detailsArea.text = """
                        |Timestamp: ${selected.timestamp}
                        |Type: ${selected.type}
                        |Message: ${selected.message}
                        |
                        |Details:
                        |${selected.details.ifEmpty { "(none)" }}
                    """.trimMargin()
                    detailsArea.caretPosition = 0
                }
            }
        }
    }

    private fun createDebugToolbar(
        debugModel: DefaultListModel<CopilotAcpClient.DebugEvent>,
        list: JBList<CopilotAcpClient.DebugEvent>
    ): JBPanel<JBPanel<*>> {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))
        val clearBtn = JButton("Clear")
        clearBtn.addActionListener { debugModel.clear() }

        val copyBtn = JButton("Copy Selected")
        copyBtn.addActionListener {
            val selected = list.selectedValue
            if (selected != null) {
                val content =
                    "${selected.timestamp} [${selected.type}] ${selected.message}\n${selected.details}"
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(content), null
                )
            }
        }

        val exportBtn = JButton("Export All")
        exportBtn.addActionListener { exportDebugEvents(debugModel) }

        toolbar.add(clearBtn)
        toolbar.add(copyBtn)
        toolbar.add(exportBtn)
        return toolbar
    }

    private fun exportDebugEvents(debugModel: DefaultListModel<CopilotAcpClient.DebugEvent>) {
        val sb = StringBuilder()
        for (i in 0 until debugModel.size()) {
            val event = debugModel.getElementAt(i)
            sb.append("${event.timestamp} [${event.type}] ${event.message}\n")
            if (event.details.isNotEmpty()) {
                sb.append("  ${event.details}\n")
            }
            sb.append("\n")
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            StringSelection(sb.toString()), null
        )
    }

    private fun registerDebugListener(
        debugModel: DefaultListModel<CopilotAcpClient.DebugEvent>,
        list: JBList<CopilotAcpClient.DebugEvent>
    ) {
        val listener: (CopilotAcpClient.DebugEvent) -> Unit = { event ->
            SwingUtilities.invokeLater {
                debugModel.addElement(event)
                while (debugModel.size() > 500) {
                    debugModel.remove(0)
                }
                list.ensureIndexIsVisible(debugModel.size() - 1)
            }
        }
        val copilotService = CopilotService.getInstance(project)
        try {
            val client = copilotService.getClient()
            client.addDebugListener(listener)
        } catch (_: Exception) {
            // Client not started yet — will add listener when it starts
        }
    }

    fun createLogViewerTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        val logArea = JBTextArea()
        logArea.isEditable = false
        logArea.font = Font("JetBrains Mono", Font.PLAIN, JBUI.Fonts.label().size - 1)

        val logFile = java.io.File(
            com.intellij.openapi.application.PathManager.getLogPath(), "idea.log"
        )
        val pluginPrefixes = listOf(
            "com.github.copilot", "CopilotAcp", "CopilotService",
            "PsiBridge", "McpServer", "ContextSnippet", "Copilot Bridge"
        )
        val loadLogs = {
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val lines = try {
                    if (logFile.exists()) {
                        logFile.useLines { seq ->
                            seq.filter { line -> pluginPrefixes.any { p -> line.contains(p) } }
                                .toList()
                                .takeLast(500)
                        }
                    } else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                SwingUtilities.invokeLater {
                    logArea.text = if (lines.isEmpty()) "No plugin logs found."
                    else lines.joinToString("\n")
                    logArea.caretPosition = logArea.text.length
                }
            }
        }
        loadLogs()

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))
        val refreshBtn = JButton("Refresh")
        refreshBtn.addActionListener { loadLogs() }
        val copyBtn = JButton("Copy All")
        copyBtn.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                StringSelection(logArea.text), null
            )
        }
        val openLogBtn = JButton("Open Full Log")
        openLogBtn.addActionListener {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(logFile)
            if (vf != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
        toolbar.add(refreshBtn)
        toolbar.add(copyBtn)
        toolbar.add(openLogBtn)

        panel.add(JBScrollPane(logArea), BorderLayout.CENTER)
        panel.add(toolbar, BorderLayout.SOUTH)
        return panel
    }

    fun openSessionFiles() {
        val agentWorkDir = java.io.File(project.basePath ?: return, AGENT_WORK_DIR)
        if (!agentWorkDir.exists()) {
            agentWorkDir.mkdirs()
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(agentWorkDir)
        if (vf != null) {
            com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, vf, true)
        }
    }

    fun createSettingsTab(): Pair<JComponent, () -> Unit> {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        val agentLabel = JBLabel("<html><b>Agent behavior</b></html>")
        gbc.gridwidth = 2
        panel.add(agentLabel, gbc)

        gbc.gridy++
        gbc.gridwidth = 1
        panel.add(JBLabel("Inactivity timeout (seconds):"), gbc)

        gbc.gridx = 1
        val timeoutSpinner = JSpinner(SpinnerNumberModel(CopilotSettings.getPromptTimeout(), 30, 600, 30))
        timeoutSpinner.toolTipText =
            "Stop agent after this many seconds of no activity. Includes model thinking time, so keep generous for complex tasks (default 300s)"
        panel.add(timeoutSpinner, gbc)

        gbc.gridx = 0
        gbc.gridy++
        panel.add(JBLabel("Max tool calls per turn:"), gbc)

        gbc.gridx = 1
        val toolCallSpinner = JSpinner(SpinnerNumberModel(CopilotSettings.getMaxToolCallsPerTurn(), 0, 500, 10))
        toolCallSpinner.toolTipText = "Limit tool calls per turn to control credit usage (0 = unlimited)"
        panel.add(toolCallSpinner, gbc)

        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JBPanel<JBPanel<*>>(), gbc)

        val saveCallback: () -> Unit = {
            CopilotSettings.setPromptTimeout(timeoutSpinner.value as Int)
            CopilotSettings.setMaxToolCallsPerTurn(toolCallSpinner.value as Int)
        }

        return Pair(panel, saveCallback)
    }

    fun openSettings() {
        val (settingsPanel, saveCallback) = createSettingsTab()
        val dialog = object : com.intellij.openapi.ui.DialogWrapper(project, true) {
            init {
                title = "Copilot Bridge Settings"
                setOKButtonText("Apply")
                init()
            }

            override fun createCenterPanel(): JComponent {
                val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
                wrapper.preferredSize = JBUI.size(450, 400)
                wrapper.add(settingsPanel, BorderLayout.CENTER)
                return wrapper
            }

            override fun doOKAction() {
                saveCallback()
                super.doOKAction()
            }
        }
        dialog.show()
    }

    fun openDebug() {
        val dialog = object : com.intellij.openapi.ui.DialogWrapper(project, true) {
            init {
                title = "Copilot Bridge Debug"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
                wrapper.preferredSize = JBUI.size(700, 550)
                val tabs = JBTabbedPane()
                tabs.addTab("Events", createDebugTab())
                tabs.addTab("Timeline", createTimelineTab())
                tabs.addTab("Plugin Logs", createLogViewerTab())
                wrapper.add(tabs, BorderLayout.CENTER)
                return wrapper
            }
        }
        dialog.show()
    }
}

internal data class TimelineEvent(
    val type: EventType,
    val message: String,
    val timestamp: java.util.Date
)

internal enum class EventType {
    SESSION_START,
    MESSAGE_SENT,
    RESPONSE_RECEIVED,
    ERROR,
    TOOL_CALL
}
