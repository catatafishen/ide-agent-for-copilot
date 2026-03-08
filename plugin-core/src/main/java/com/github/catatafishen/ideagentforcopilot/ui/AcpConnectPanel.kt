package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.AgentType
import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

/**
 * Pre-connection landing panel with two sections:
 * 1. ACP — agent connection (agent selector, custom command, connect button)
 * 2. MCP — tool server controls (start/stop, port, auto-start, real-time tool call log)
 */
class AcpConnectPanel(
    private val project: Project,
    private val onConnect: (AgentType, String?) -> Unit
) : JBPanel<AcpConnectPanel>(BorderLayout()) {

    private val agentManager = ActiveAgentManager.getInstance(project)
    private lateinit var agentCombo: JComboBox<AgentType>
    private val customCommandField = JBTextField()
    private val customCommandLabel = JBLabel("Start command:")
    private val connectButton = JButton("Connect")
    private val autoConnectCheckbox = JCheckBox("Auto-connect on startup")
    private val statusBanner = StatusBanner(project)

    // MCP section
    private val mcpStartStopButton = JButton("Start")
    private val mcpPortField = JBTextField(6)
    private val mcpAutoStartCheckbox = JCheckBox("Auto-start on IDE open")
    private val toolCallLogModel = DefaultListModel<String>()
    private val toolCallList = JList(toolCallLogModel)

    init {
        border = JBUI.Borders.empty(12, 24)

        val mainBox = JBPanel<JBPanel<*>>()
        mainBox.layout = BoxLayout(mainBox, BoxLayout.Y_AXIS)
        mainBox.isOpaque = false

        mainBox.add(createAcpSection())
        mainBox.add(Box.createVerticalStrut(20))
        mainBox.add(createMcpSection())

        val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(mainBox, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(wrapper)
        scrollPane.border = null
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        add(scrollPane, BorderLayout.CENTER)

        subscribeToBridgeEvents()
        refreshMcpState()
    }

    private fun createAcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>()
        section.layout = BoxLayout(section, BoxLayout.Y_AXIS)
        section.isOpaque = false
        section.alignmentX = LEFT_ALIGNMENT

        val titleLabel = JBLabel("ACP \u2014 Agent connection")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = LEFT_ALIGNMENT
        section.add(titleLabel)
        section.add(Box.createVerticalStrut(8))

        // Agent selector
        val agentLabel = JBLabel("Agent:")
        agentLabel.alignmentX = LEFT_ALIGNMENT
        section.add(agentLabel)
        section.add(Box.createVerticalStrut(4))

        agentCombo = JComboBox(AgentType.entries.toTypedArray())
        agentCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? AgentType)?.displayName() ?: ""
                return this
            }
        }
        agentCombo.selectedItem = agentManager.activeType
        agentCombo.maximumSize = Dimension(300, agentCombo.preferredSize.height)
        agentCombo.alignmentX = LEFT_ALIGNMENT
        agentCombo.addActionListener { updateCustomCommandVisibility() }
        section.add(agentCombo)
        section.add(Box.createVerticalStrut(8))

        // Custom command field
        customCommandLabel.alignmentX = LEFT_ALIGNMENT
        section.add(customCommandLabel)
        section.add(Box.createVerticalStrut(4))

        customCommandField.text = agentManager.getCustomAcpCommandFor(agentManager.activeType)
        customCommandField.maximumSize = Dimension(400, customCommandField.preferredSize.height)
        customCommandField.alignmentX = LEFT_ALIGNMENT
        section.add(customCommandField)
        section.add(Box.createVerticalStrut(12))

        // Connect button
        connectButton.alignmentX = LEFT_ALIGNMENT
        connectButton.addActionListener { doConnect() }
        section.add(connectButton)
        section.add(Box.createVerticalStrut(8))

        // Auto-connect checkbox
        autoConnectCheckbox.isSelected = agentManager.isAutoConnect
        autoConnectCheckbox.alignmentX = LEFT_ALIGNMENT
        autoConnectCheckbox.addActionListener {
            agentManager.isAutoConnect = autoConnectCheckbox.isSelected
        }
        section.add(autoConnectCheckbox)
        section.add(Box.createVerticalStrut(4))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        section.add(statusBanner)

        updateCustomCommandVisibility()
        return section
    }

    private fun createMcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>()
        section.layout = BoxLayout(section, BoxLayout.Y_AXIS)
        section.isOpaque = false
        section.alignmentX = LEFT_ALIGNMENT

        val titleLabel = JBLabel("MCP \u2014 Tool server")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.alignmentX = LEFT_ALIGNMENT
        section.add(titleLabel)
        section.add(Box.createVerticalStrut(8))

        // Port + Start/Stop row
        val controlRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0))
        controlRow.isOpaque = false
        controlRow.alignmentX = LEFT_ALIGNMENT

        controlRow.add(JBLabel("Port:"))
        mcpPortField.text = formatPort(CopilotSettings.getMcpPort())
        mcpPortField.toolTipText = "0 or empty = auto-assign random port"
        controlRow.add(mcpPortField)
        controlRow.add(mcpStartStopButton)

        mcpStartStopButton.addActionListener { toggleMcpServer() }
        section.add(controlRow)
        section.add(Box.createVerticalStrut(6))

        // Auto-start checkbox
        mcpAutoStartCheckbox.isSelected = CopilotSettings.getMcpAutoStart()
        mcpAutoStartCheckbox.alignmentX = LEFT_ALIGNMENT
        mcpAutoStartCheckbox.addActionListener {
            CopilotSettings.setMcpAutoStart(mcpAutoStartCheckbox.isSelected)
        }
        section.add(mcpAutoStartCheckbox)
        section.add(Box.createVerticalStrut(10))

        // Tool call log
        val logLabel = JBLabel("Recent tool calls:")
        logLabel.font = logLabel.font.deriveFont(Font.PLAIN, 11f)
        logLabel.foreground = UIUtil.getLabelInfoForeground()
        logLabel.alignmentX = LEFT_ALIGNMENT
        section.add(logLabel)
        section.add(Box.createVerticalStrut(4))

        toolCallList.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        toolCallList.visibleRowCount = 8
        toolCallList.cellRenderer = ToolCallCellRenderer()
        val scrollPane = JBScrollPane(toolCallList)
        scrollPane.alignmentX = LEFT_ALIGNMENT
        scrollPane.preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(140))
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(300))
        section.add(scrollPane)

        return section
    }

    private fun subscribeToBridgeEvents() {
        val connection = project.messageBus.connect()

        connection.subscribe(
            PsiBridgeService.STATUS_TOPIC,
            PsiBridgeService.StatusListener { _ ->
                SwingUtilities.invokeLater { refreshMcpState() }
            })

        connection.subscribe(
            PsiBridgeService.TOOL_CALL_TOPIC,
            PsiBridgeService.ToolCallListener { toolName, durationMs, success ->
                SwingUtilities.invokeLater { addToolCallEntry(toolName, durationMs, success) }
            })
    }

    private fun refreshMcpState() {
        val bridge = PsiBridgeService.getInstance(project)
        val running = bridge.isRunning
        val port = bridge.port

        mcpStartStopButton.text = if (running) "Stop" else "Start"
        if (running && port > 0) {
            mcpPortField.text = port.toString()
            mcpPortField.isEnabled = false
        } else {
            mcpPortField.isEnabled = true
            if (mcpPortField.text.isBlank() || mcpPortField.text == "0") {
                mcpPortField.text = formatPort(CopilotSettings.getMcpPort())
            }
        }
    }

    private fun toggleMcpServer() {
        val bridge = PsiBridgeService.getInstance(project)
        if (bridge.isRunning) {
            bridge.stop()
        } else {
            val portText = mcpPortField.text.trim()
            val port = portText.toIntOrNull() ?: 0
            CopilotSettings.setMcpPort(port)
            bridge.start(port)
        }
        refreshMcpState()
    }

    private fun addToolCallEntry(toolName: String, durationMs: Long, success: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val status = if (success) "\u2713" else "\u2717"
        val entry = "$time  $status  $toolName  (${durationMs}ms)"

        toolCallLogModel.addElement(entry)

        // Keep last 200 entries
        while (toolCallLogModel.size() > 200) {
            toolCallLogModel.removeElementAt(0)
        }

        // Auto-scroll to bottom
        toolCallList.ensureIndexIsVisible(toolCallLogModel.size() - 1)
    }

    private fun formatPort(port: Int): String = if (port == 0) "0" else port.toString()

    private fun updateCustomCommandVisibility() {
        val selectedType = agentCombo.selectedItem as? AgentType ?: return
        customCommandLabel.isVisible = true
        customCommandField.isVisible = true

        if (!customCommandField.hasFocus()) {
            val stored = agentManager.getCustomAcpCommandFor(selectedType)
            customCommandField.text = stored
        }

        customCommandField.emptyText.text = if (selectedType == AgentType.GENERIC) {
            "e.g., my-agent --acp --stdio"
        } else {
            selectedType.defaultStartCommand()
        }
    }

    private fun doConnect() {
        val selectedType = agentCombo.selectedItem as AgentType
        val cmd = customCommandField.text.trim()

        if (cmd.isEmpty()) {
            statusBanner.showError("Enter a start command for the agent.")
            return
        }

        agentManager.setCustomAcpCommandFor(selectedType, cmd)

        val customCommand = if (selectedType == AgentType.GENERIC || cmd != selectedType.defaultStartCommand()) {
            cmd
        } else {
            null
        }

        statusBanner.dismissCurrent()
        connectButton.isEnabled = false
        connectButton.text = "Connecting..."
        onConnect(selectedType, customCommand)
    }

    fun showError(message: String) {
        SwingUtilities.invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            statusBanner.showError(message)
        }
    }

    fun resetConnectButton() {
        SwingUtilities.invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
        }
    }

    fun refreshMcpStatus() {
        refreshMcpState()
    }

    /**
     * Custom cell renderer that colors failed tool calls in red.
     */
    private class ToolCallCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val text = value?.toString() ?: ""
            if (!isSelected && text.contains("  \u2717  ")) {
                foreground = JBColor.RED
            }
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            return this
        }
    }
}
