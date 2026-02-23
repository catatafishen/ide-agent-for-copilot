package com.github.copilot.intellij.ui

import com.github.copilot.intellij.bridge.CopilotAcpClient
import com.github.copilot.intellij.services.CopilotService
import com.github.copilot.intellij.services.CopilotSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Main content for the Agentic Copilot tool window.
 * Uses Kotlin UI DSL for cleaner, more maintainable UI code.
 */
class AgenticCopilotToolWindowContent(private val project: Project) {

    // UI String Constants
    private companion object {
        const val MSG_LOADING = "Loading..."
        const val MSG_THINKING = "Thinking..."
        const val MSG_UNKNOWN_ERROR = "Unknown error"
        const val PROMPT_PLACEHOLDER = "Ask Copilot... (Shift+Enter for new line)"
    }

    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val tabbedPane = JBTabbedPane()

    // Shared context list across tabs
    private val contextListModel = DefaultListModel<ContextItem>()

    // Shared model list (populated from ACP)
    private var loadedModels: List<CopilotAcpClient.Model> = emptyList()

    // Current conversation session — reused for multi-turn
    private var currentSessionId: String? = null

    // Prompt tab fields (promoted from local variables for footer layout)
    private lateinit var modelComboBox: ComboBox<String>
    private lateinit var promptTextArea: JBTextArea
    private lateinit var sendStopButton: JButton
    private var currentPromptThread: Thread? = null
    private var isSending = false

    // Timeline events (populated from ACP session/update notifications)
    private val timelineModel = DefaultListModel<TimelineEvent>()

    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    private lateinit var sessionInfoLabel: JBLabel

    // Usage display components (updated after each prompt)
    private lateinit var usageLabel: JBLabel
    private lateinit var costLabel: JBLabel
    private lateinit var responseTextArea: JBTextArea
    private lateinit var responseSpinner: AsyncProcessIcon
    private lateinit var responseStatus: JBLabel

    init {
        setupUI()
    }

    private fun setupUI() {
        // Create tabs
        tabbedPane.addTab("Prompt", createPromptTab())
        tabbedPane.addTab("Context", createContextTab())
        tabbedPane.addTab("Session", createSessionTab())
        tabbedPane.addTab("Timeline", createTimelineTab())
        tabbedPane.addTab("Debug", createDebugTab())
        tabbedPane.addTab("Settings", createSettingsTab())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)
    }

    /** Record an event in the Timeline tab. Thread-safe. */
    private fun addTimelineEvent(type: EventType, message: String) {
        SwingUtilities.invokeLater {
            timelineModel.addElement(TimelineEvent(type, message, java.util.Date()))
        }
    }

    private fun updateSessionInfo() {
        SwingUtilities.invokeLater {
            if (!::sessionInfoLabel.isInitialized) return@invokeLater
            val sid = currentSessionId
            if (sid != null) {
                val shortId = sid.take(8) + "..."
                val cwd = project.basePath ?: "unknown"
                sessionInfoLabel.text = "Session: $shortId  ·  $cwd"
                sessionInfoLabel.foreground = JBColor.foreground()
            } else {
                sessionInfoLabel.text = "No active session"
                sessionInfoLabel.foreground = JBColor.GRAY
            }
        }
    }

    // Track tool calls for Session tab file correlation
    private val toolCallFiles = mutableMapOf<String, String>() // toolCallId -> file path

    /** Handle ACP session/update notifications — routes to timeline and session tab. */
    private fun handleAcpUpdate(update: com.google.gson.JsonObject) {
        val updateType = update["sessionUpdate"]?.asString ?: return

        when (updateType) {
            "tool_call" -> handleToolCall(update)
            "tool_call_update" -> handleToolCallUpdate(update)
            "plan" -> handlePlanUpdate(update)
        }
    }

    private fun handleToolCall(update: com.google.gson.JsonObject) {
        val title = update["title"]?.asString ?: "Unknown tool"
        val status = update["status"]?.asString ?: "pending"
        val toolCallId = update["toolCallId"]?.asString ?: ""
        addTimelineEvent(EventType.TOOL_CALL, "$title ($status)")

        val filePath = extractFilePath(update, title)
        if (filePath != null && toolCallId.isNotEmpty()) {
            toolCallFiles[toolCallId] = filePath
        }
    }

    private fun extractFilePath(update: com.google.gson.JsonObject, title: String): String? {
        val locations = if (update.has("locations")) update.getAsJsonArray("locations") else null
        if (locations != null && locations.size() > 0) {
            val path = locations[0].asJsonObject["path"]?.asString
            if (path != null) return path
        }
        val pathMatch = Regex("""(?:Creating|Writing|Editing|Reading)\s+(.+\.\w+)""").find(title)
        return pathMatch?.groupValues?.get(1)
    }

    private fun handleToolCallUpdate(update: com.google.gson.JsonObject) {
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        if (status != "completed" && status != "failed") return

        addTimelineEvent(EventType.TOOL_CALL, "Tool $toolCallId $status")

        val filePath = toolCallFiles[toolCallId]
        if (status == "completed" && filePath != null) {
            loadCompletedToolFile(filePath)
        }
    }

    private fun loadCompletedToolFile(filePath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = java.io.File(filePath)
                if (file.exists() && file.length() < 100_000) {
                    val content = file.readText()
                    SwingUtilities.invokeLater {
                        val fileNode = FileTreeNode(file.name, filePath, content)
                        planRoot.add(fileNode)
                        planTreeModel.reload()
                        planDetailsArea.text = "${file.name}\n${"—".repeat(40)}\n\n$content"
                    }
                }
            } catch (_: Exception) {
                // Plan file loading is best-effort; errors are non-critical
            }
        }
    }

    private fun handlePlanUpdate(update: com.google.gson.JsonObject) {
        val entries = update.getAsJsonArray("entries") ?: return
        SwingUtilities.invokeLater {
            // Remove existing plan group, but keep Files group
            val toRemove = mutableListOf<javax.swing.tree.DefaultMutableTreeNode>()
            for (i in 0 until planRoot.childCount) {
                val child = planRoot.getChildAt(i) as javax.swing.tree.DefaultMutableTreeNode
                if (child.userObject == "Plan") toRemove.add(child)
            }
            toRemove.forEach { planRoot.remove(it) }

            val planNode = javax.swing.tree.DefaultMutableTreeNode("Plan")
            for (entry in entries) {
                val obj = entry.asJsonObject
                val content = obj["content"]?.asString ?: "Step"
                val entryStatus = obj["status"]?.asString ?: "pending"
                val priority = obj["priority"]?.asString ?: ""
                val label = "$content [$entryStatus]${if (priority.isNotEmpty()) " ($priority)" else ""}"
                planNode.add(javax.swing.tree.DefaultMutableTreeNode(label))
            }
            planRoot.add(planNode)
            planTreeModel.reload()
            addTimelineEvent(EventType.TOOL_CALL, "Plan updated (${entries.size()} steps)")
        }
    }

    /**
     * Loads real billing data from GitHub's internal Copilot API via gh CLI.
     * Shows premium request quota, usage, and overage info.
     */
    private fun loadBillingData() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val ghCli = findGhCli() ?: run {
                    updateUsageUi(
                        "Usage info unavailable (gh CLI not found)",
                        "Install GitHub CLI: https://cli.github.com  then run 'gh auth login'"
                    )
                    return@executeOnPooledThread
                }

                if (!isGhAuthenticated(ghCli)) {
                    updateUsageUi(
                        "Usage info unavailable (not authenticated)",
                        "Run 'gh auth login' in a terminal to authenticate with GitHub"
                    )
                    return@executeOnPooledThread
                }

                val obj = fetchCopilotUserData(ghCli) ?: return@executeOnPooledThread
                val snapshots = obj.getAsJsonObject("quota_snapshots") ?: return@executeOnPooledThread
                val premium = snapshots.getAsJsonObject("premium_interactions") ?: return@executeOnPooledThread

                displayBillingQuota(premium, obj)
            } catch (e: Exception) {
                updateUsageUi(
                    "Usage info unavailable",
                    "Error: ${e.message}. Ensure 'gh auth login' has been run."
                )
            }
        }
    }

    private fun updateUsageUi(text: String, tooltip: String, cost: String = "") {
        SwingUtilities.invokeLater {
            usageLabel.text = text
            usageLabel.toolTipText = tooltip
            costLabel.text = cost
        }
    }

    private fun isGhAuthenticated(ghCli: String): Boolean {
        val process = ProcessBuilder(ghCli, "auth", "status").redirectErrorStream(true).start()
        val authOutput = process.inputStream.bufferedReader().readText()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        return process.exitValue() == 0 && "not logged in" !in authOutput.lowercase() && "gh auth login" !in authOutput
    }

    private fun fetchCopilotUserData(ghCli: String): com.google.gson.JsonObject? {
        val apiProcess = ProcessBuilder(ghCli, "api", "/copilot_internal/user").redirectErrorStream(true).start()
        val json = apiProcess.inputStream.bufferedReader().readText()
        apiProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        return com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
    }

    private fun displayBillingQuota(premium: com.google.gson.JsonObject, obj: com.google.gson.JsonObject) {
        val entitlement = premium["entitlement"]?.asInt ?: 0
        val remaining = premium["remaining"]?.asInt ?: 0
        val unlimited = premium["unlimited"]?.asBoolean ?: false
        val overagePermitted = premium["overage_permitted"]?.asBoolean ?: false
        val resetDate = obj["quota_reset_date"]?.asString ?: ""
        val used = entitlement - remaining

        SwingUtilities.invokeLater {
            if (unlimited) {
                usageLabel.text = "Unlimited premium requests"
                usageLabel.toolTipText = "Resets $resetDate"
                costLabel.text = ""
            } else {
                usageLabel.text = "$used / $entitlement premium requests"
                usageLabel.toolTipText = "Resets $resetDate"
                updateCostLabel(remaining, overagePermitted)
            }
        }
    }

    private fun updateCostLabel(remaining: Int, overagePermitted: Boolean) {
        if (remaining < 0) {
            val overageCost = -remaining * 0.04
            costLabel.text = if (overagePermitted) {
                "Est. overage: $${String.format("%.2f", overageCost)}"
            } else {
                "Quota exceeded - overages not permitted"
            }
            costLabel.foreground = JBColor.RED
        } else {
            costLabel.text = ""
        }
    }

    /**
     * Finds the gh CLI executable, checking PATH and known install locations.
     */
    private fun findGhCli(): String? {
        // Check PATH using platform-appropriate command
        try {
            val cmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val check = ProcessBuilder(cmd, "gh").start()
            if (check.waitFor() == 0) return "gh"
        } catch (_: Exception) {
            // gh CLI detection is best-effort
        }

        // Check known install locations
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val knownPaths = if (isWindows) {
            listOf(
                "C:\\Program Files\\GitHub CLI\\gh.exe",
                "C:\\Program Files (x86)\\GitHub CLI\\gh.exe",
                "C:\\Tools\\gh\\bin\\gh.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\GitHub CLI\\gh.exe"
            )
        } else {
            listOf(
                "/usr/bin/gh",
                "/usr/local/bin/gh",
                System.getProperty("user.home") + "/.local/bin/gh",
                "/snap/bin/gh",
                "/home/linuxbrew/.linuxbrew/bin/gh"
            )
        }
        return knownPaths.firstOrNull { java.io.File(it).exists() }
    }

    /**
     * Launches the Copilot CLI auth flow in a new command window.
     * Uses the auth method from the ACP initialize response if available.
     */
    private fun startCopilotLogin() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Try to get auth method from ACP client
                val service = CopilotService.getInstance(project)
                var authCommand: String? = null
                var authArgs: List<String>? = null

                try {
                    val client = service.getClient()
                    val authMethod = client.authMethod
                    if (authMethod?.command != null) {
                        authCommand = authMethod.command
                        authArgs = authMethod.args
                    }
                } catch (_: Exception) {
                    // Auth method extraction is best-effort
                }

                if (authCommand != null) {
                    val cmd = mutableListOf("cmd", "/c", "start", "cmd", "/k", "\"$authCommand\"")
                    authArgs?.forEach { cmd.add(it) }
                    ProcessBuilder(cmd).start()
                } else {
                    // Fallback: try copilot login
                    ProcessBuilder("cmd", "/c", "start", "cmd", "/k", "copilot login").start()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        mainPanel,
                        "Failed to start auth flow: ${e.message}\n\nPlease run 'copilot login' in your terminal.",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun createPromptTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())

        // Auth status panel (shown on error only)
        val authPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        authPanel.isVisible = false
        authPanel.border = JBUI.Borders.emptyLeft(5)
        val modelErrorLabel = JBLabel()
        val loginButton = JButton("Login")
        val retryButton = JButton("Retry")
        createAuthButtons(modelErrorLabel, loginButton, retryButton, authPanel)

        // Response/chat history area (CENTER)
        val responsePanel = createResponsePanel()
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        topPanel.add(authPanel, BorderLayout.NORTH)
        topPanel.add(responsePanel, BorderLayout.CENTER)
        panel.add(topPanel, BorderLayout.CENTER)

        // Footer: input row + controls row (SOUTH)
        val footer = createChatFooter()
        panel.add(footer, BorderLayout.SOUTH)

        loadBillingData()

        // Load models
        fun loadModels() {
            loadModelsAsync(
                footer.getClientProperty("loadingSpinner") as AsyncProcessIcon,
                modelComboBox,
                modelErrorLabel,
                loginButton,
                retryButton,
                authPanel
            ) { models ->
                loadedModels = models
            }
        }

        retryButton.addActionListener { loadModels() }
        loadModels()

        return panel
    }

    private fun createChatFooter(): JBPanel<JBPanel<*>> {
        val footer = JBPanel<JBPanel<*>>()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8),
            true
        )

        // Row 1: Input with send/stop button
        val inputRow = createInputRow()
        inputRow.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(inputRow)

        footer.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Row 2: Model, mode, status, usage
        val loadingSpinner = AsyncProcessIcon("loading-models")
        loadingSpinner.preferredSize = JBUI.size(16, 16)
        footer.putClientProperty("loadingSpinner", loadingSpinner)

        val controlsRow = createControlsRow(loadingSpinner)
        controlsRow.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(controlsRow)

        return footer
    }

    private fun createInputRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(4), 0))

        promptTextArea = JBTextArea()
        promptTextArea.lineWrap = true
        promptTextArea.wrapStyleWord = true
        promptTextArea.rows = 2
        promptTextArea.border = JBUI.Borders.empty(4)

        setupPromptKeyBindings(promptTextArea)
        setupPromptPlaceholder(promptTextArea)

        // Auto-resize rows (2-6)
        promptTextArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = adjustRows()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = adjustRows()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {}
            private fun adjustRows() {
                val newRows = promptTextArea.lineCount.coerceIn(2, 6)
                if (promptTextArea.rows != newRows) {
                    promptTextArea.rows = newRows
                    SwingUtilities.invokeLater { promptTextArea.revalidate() }
                }
            }
        })

        val scrollPane = JBScrollPane(promptTextArea)
        scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1)
        row.add(scrollPane, BorderLayout.CENTER)

        // Send/stop toggle button
        sendStopButton = JButton(com.intellij.icons.AllIcons.Actions.Execute)
        sendStopButton.toolTipText = "Send (Enter)"
        sendStopButton.preferredSize = JBUI.size(32, 32)
        sendStopButton.isFocusable = false
        sendStopButton.addActionListener { onSendStopClicked() }

        // Connect Enter key
        promptTextArea.actionMap.put("sendPrompt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (promptTextArea.text.isNotBlank() && promptTextArea.text != PROMPT_PLACEHOLDER && !isSending) {
                    onSendStopClicked()
                }
            }
        })

        val buttonWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        buttonWrapper.add(sendStopButton, BorderLayout.SOUTH)
        row.add(buttonWrapper, BorderLayout.EAST)

        return row
    }

    private fun onSendStopClicked() {
        if (isSending) {
            handleStopRequest(currentPromptThread)
            setSendingState(false)
        } else {
            val prompt = promptTextArea.text.trim()
            if (prompt.isEmpty() || prompt == PROMPT_PLACEHOLDER) return

            setSendingState(true)
            setResponseStatus(MSG_THINKING)

            if (currentSessionId == null) {
                responseTextArea.text = ""
            }
            appendResponse("\n>>> $prompt\n\n")
            promptTextArea.text = ""

            ApplicationManager.getApplication().executeOnPooledThread {
                currentPromptThread = Thread.currentThread()
                executePrompt(prompt)
                currentPromptThread = null
            }
        }
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        SwingUtilities.invokeLater {
            if (sending) {
                sendStopButton.icon = com.intellij.icons.AllIcons.Actions.Suspend
                sendStopButton.toolTipText = "Stop"
            } else {
                sendStopButton.icon = com.intellij.icons.AllIcons.Actions.Execute
                sendStopButton.toolTipText = "Send (Enter)"
            }
        }
    }

    private fun createControlsRow(loadingSpinner: AsyncProcessIcon): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE

        // Model selector
        gbc.gridx = 0
        gbc.insets = JBUI.insetsRight(4)
        modelComboBox = ComboBox(arrayOf(MSG_LOADING))
        modelComboBox.preferredSize = JBUI.size(240, 26)
        modelComboBox.isEnabled = false
        modelComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                return this
            }
        }
        row.add(modelComboBox, gbc)

        // Loading spinner
        gbc.gridx++
        gbc.insets = JBUI.insetsRight(8)
        row.add(loadingSpinner, gbc)

        // Mode selector
        gbc.gridx++
        gbc.insets = JBUI.insetsRight(8)
        val modeCombo = ComboBox(arrayOf("Agent", "Plan"))
        modeCombo.preferredSize = JBUI.size(90, 26)
        modeCombo.selectedItem = if (CopilotSettings.getSessionMode() == "plan") "Plan" else "Agent"
        modeCombo.addActionListener {
            val mode = if (modeCombo.selectedItem == "Plan") "plan" else "agent"
            CopilotSettings.setSessionMode(mode)
        }
        row.add(modeCombo, gbc)

        // Status
        gbc.gridx++
        gbc.insets = JBUI.insetsRight(4)
        responseSpinner = AsyncProcessIcon("response-loading")
        responseSpinner.preferredSize = JBUI.size(16, 16)
        responseSpinner.isVisible = false
        row.add(responseSpinner, gbc)

        gbc.gridx++
        gbc.insets = JBUI.insetsRight(4)
        responseStatus = JBLabel("")
        responseStatus.font = JBUI.Fonts.smallFont()
        responseStatus.foreground = JBColor.GRAY
        row.add(responseStatus, gbc)

        // Spacer
        gbc.gridx++
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        row.add(Box.createHorizontalGlue(), gbc)

        // Usage (right-aligned)
        gbc.gridx++
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.insetsRight(4)
        usageLabel = JBLabel("")
        usageLabel.font = JBUI.Fonts.smallFont()
        row.add(usageLabel, gbc)

        gbc.gridx++
        gbc.insets = JBUI.emptyInsets()
        costLabel = JBLabel("")
        costLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        row.add(costLabel, gbc)

        return row
    }

    private fun createResponsePanel(): JBPanel<JBPanel<*>> {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        responseTextArea = JBTextArea()
        responseTextArea.isEditable = false
        responseTextArea.lineWrap = true
        responseTextArea.wrapStyleWord = true
        responseTextArea.text = "Start a conversation with Copilot...\n\nFirst run will auto-start the Copilot process."
        responseTextArea.foreground = JBColor.GRAY

        val responseScrollPane = JBScrollPane(responseTextArea)
        responseScrollPane.border = null
        panel.add(responseScrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun appendResponse(text: String) {
        SwingUtilities.invokeLater {
            // Clear placeholder styling on first real content
            if (responseTextArea.foreground == JBColor.GRAY) {
                responseTextArea.foreground = UIManager.getColor("TextArea.foreground")
            }
            responseTextArea.append(text)
            responseTextArea.caretPosition = responseTextArea.document.length
        }
    }

    private fun setResponseStatus(text: String, loading: Boolean = true) {
        SwingUtilities.invokeLater {
            responseStatus.text = text
            responseSpinner.isVisible = loading
        }
    }

    private fun setupPromptKeyBindings(promptTextArea: JBTextArea) {
        val enterKey = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        val shiftEnterKey = KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_ENTER,
            java.awt.event.InputEvent.SHIFT_DOWN_MASK
        )
        promptTextArea.getInputMap(JComponent.WHEN_FOCUSED).put(enterKey, "sendPrompt")
        promptTextArea.actionMap.put("sendPrompt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                // Will be connected via button click
            }
        })
        promptTextArea.getInputMap(JComponent.WHEN_FOCUSED).put(shiftEnterKey, "insertNewline")
        promptTextArea.actionMap.put("insertNewline", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                promptTextArea.insert("\n", promptTextArea.caretPosition)
            }
        })
    }

    private fun setupPromptPlaceholder(promptTextArea: JBTextArea) {
        promptTextArea.addFocusListener(object : java.awt.event.FocusListener {
            private val placeholder = PROMPT_PLACEHOLDER
            override fun focusGained(e: java.awt.event.FocusEvent) {
                if (promptTextArea.text == placeholder) {
                    promptTextArea.text = ""
                    promptTextArea.foreground = UIManager.getColor("TextArea.foreground")
                }
            }

            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (promptTextArea.text.isBlank()) {
                    promptTextArea.text = placeholder
                    promptTextArea.foreground = JBColor.GRAY
                }
            }
        })
        promptTextArea.text = PROMPT_PLACEHOLDER
        promptTextArea.foreground = JBColor.GRAY
    }

    private fun handleStopRequest(promptThread: Thread?) {
        val sessionId = currentSessionId
        if (sessionId != null) {
            try {
                CopilotService.getInstance(project).getClient().cancelSession(sessionId)
            } catch (_: Exception) {
                // Best-effort cancellation
            }
        }
        promptThread?.interrupt()
        appendResponse("\n\u2717 Stopped by user\n")
        setResponseStatus("Stopped", loading = false)
        addTimelineEvent(EventType.ERROR, "Prompt cancelled by user")
    }

    private fun executePrompt(prompt: String) {
        try {
            val service = CopilotService.getInstance(project)
            val client = service.getClient()

            if (currentSessionId == null) {
                currentSessionId = client.createSession(project.basePath)
                addTimelineEvent(EventType.SESSION_START, "Session created")
                updateSessionInfo()
            }
            val sessionId = currentSessionId!!

            addTimelineEvent(
                EventType.MESSAGE_SENT,
                "Prompt: ${prompt.take(80)}${if (prompt.length > 80) "..." else ""}"
            )

            val selIdx = modelComboBox.selectedIndex
            val selectedModelObj =
                if (selIdx >= 0 && selIdx < loadedModels.size) loadedModels[selIdx] else null
            val modelId = selectedModelObj?.id ?: ""

            val references = buildContextReferences()
            if (references.isNotEmpty()) {
                appendResponse("\u2139 ${references.size} context file(s) attached\n\n")
            }

            var receivedContent = false

            client.sendPrompt(
                sessionId, prompt, modelId,
                references.ifEmpty { null },
                { chunk ->
                    if (!receivedContent) {
                        receivedContent = true
                        setResponseStatus("Responding...")
                    }
                    appendResponse(chunk)
                },
                { update -> handlePromptStreamingUpdate(update, receivedContent) }
            )

            appendResponse("\n")
            setResponseStatus("Done", loading = false)
            addTimelineEvent(EventType.RESPONSE_RECEIVED, "Response received")
            loadBillingData()

        } catch (e: Exception) {
            handlePromptError(e)
        } finally {
            setSendingState(false)
        }
    }

    private fun buildContextReferences(): List<CopilotAcpClient.ResourceReference> {
        val references = mutableListOf<CopilotAcpClient.ResourceReference>()
        for (i in 0 until contextListModel.size()) {
            val item = contextListModel.getElementAt(i)
            try {
                val ref = buildSingleReference(item)
                if (ref != null) references.add(ref)
            } catch (_: Exception) {
                appendResponse("\u26a0 Could not read context: ${item.name}\n")
            }
        }
        return references
    }

    private fun buildSingleReference(item: ContextItem): CopilotAcpClient.ResourceReference? {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
            ?: return null
        val doc =
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            } ?: return null

        val text = if (item.isSelection && item.startLine > 0) {
            val startOffset = doc.getLineStartOffset((item.startLine - 1).coerceIn(0, doc.lineCount - 1))
            val endOffset = doc.getLineEndOffset((item.endLine - 1).coerceIn(0, doc.lineCount - 1))
            doc.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
        } else {
            doc.text
        }

        val uri = "file://${item.path.replace("\\", "/")}"
        val mimeType = getMimeTypeForFileType(file.fileType.name.lowercase())
        return CopilotAcpClient.ResourceReference(uri, mimeType, text)
    }

    private fun getMimeTypeForFileType(fileTypeName: String): String {
        return when (fileTypeName) {
            "java" -> "text/x-java"
            "kotlin" -> "text/x-kotlin"
            "python" -> "text/x-python"
            "javascript" -> "text/javascript"
            "typescript" -> "text/typescript"
            "xml", "html" -> "text/$fileTypeName"
            else -> "text/plain"
        }
    }

    private fun handlePromptStreamingUpdate(update: com.google.gson.JsonObject, receivedContent: Boolean) {
        val updateType = update["sessionUpdate"]?.asString ?: ""
        when (updateType) {
            "tool_call" -> {
                val title = update["title"]?.asString ?: "tool"
                val status = update["status"]?.asString ?: ""
                setResponseStatus("Running: $title")
                if (status != "completed") {
                    appendResponse("\u2692 $title\n")
                }
            }

            "tool_call_update" -> {
                val status = update["status"]?.asString ?: ""
                if (status == "completed") {
                    setResponseStatus(MSG_THINKING)
                } else if (status == "failed") {
                    val toolId = update["toolCallId"]?.asString ?: ""
                    appendResponse("\u2716 Tool failed: $toolId\n")
                }
            }

            "agent_thought_chunk" -> {
                if (!receivedContent) {
                    setResponseStatus(MSG_THINKING)
                }
            }
        }
        handleAcpUpdate(update)
    }

    private fun handlePromptError(e: Exception) {
        val msg = if (e is InterruptedException || e.cause is InterruptedException) {
            "Request cancelled"
        } else {
            e.message ?: MSG_UNKNOWN_ERROR
        }
        appendResponse("\n\u274c Error: $msg\n")
        setResponseStatus("Error", loading = false)
        addTimelineEvent(EventType.ERROR, "Error: ${msg.take(80)}")

        val isRecoverable = e is InterruptedException || e.cause is InterruptedException ||
            (e is com.github.copilot.intellij.bridge.CopilotException && e.isRecoverable)
        if (!isRecoverable) {
            currentSessionId = null
            updateSessionInfo()
        }
        e.printStackTrace()
    }

    private fun createContextTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 10, 5))

        val addFileButton = JButton("Add Current File")
        addFileButton.toolTipText = "Add the currently open file to context"
        addFileButton.addActionListener { handleAddCurrentFile(panel) }
        toolbar.add(addFileButton)

        val addSelectionButton = JButton("Add Selection")
        addSelectionButton.toolTipText = "Add the current text selection to context"
        addSelectionButton.addActionListener { handleAddSelection(panel) }
        toolbar.add(addSelectionButton)

        val clearButton = JButton("Clear All")
        clearButton.addActionListener {
            if (contextListModel.size() > 0) {
                val result = JOptionPane.showConfirmDialog(
                    panel,
                    "Remove all ${contextListModel.size()} context items?",
                    "Clear Context",
                    JOptionPane.YES_NO_OPTION
                )
                if (result == JOptionPane.YES_OPTION) {
                    contextListModel.clear()
                }
            }
        }
        toolbar.add(clearButton)

        panel.add(toolbar, BorderLayout.NORTH)

        val contextList = com.intellij.ui.components.JBList(contextListModel)
        contextList.cellRenderer = createContextCellRenderer()

        val scrollPane = JBScrollPane(contextList)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Bottom info panel
        val infoPanel = JBPanel<JBPanel<*>>(BorderLayout())
        infoPanel.border = JBUI.Borders.empty(5)
        val infoLabel = JBLabel("Context items will be sent with each prompt")
        infoLabel.foreground = JBColor.GRAY
        infoPanel.add(infoLabel, BorderLayout.WEST)

        val countLabel = JBLabel("0 items")
        countLabel.foreground = JBColor.GRAY
        infoPanel.add(countLabel, BorderLayout.EAST)

        // Update count when list changes
        contextListModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = updateCount()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = updateCount()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = updateCount()

            private fun updateCount() {
                countLabel.text = "${contextListModel.size()} items"
            }
        })

        panel.add(infoPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun handleAddCurrentFile(panel: JBPanel<JBPanel<*>>) {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (currentFile == null) {
            JOptionPane.showMessageDialog(
                panel,
                "No file is currently open in the editor",
                "No File",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val path = currentFile.path
        val lineCount = try {
            fileEditorManager.selectedTextEditor?.document?.lineCount ?: 0
        } catch (_: Exception) {
            0
        }

        val exists = (0 until contextListModel.size()).any { contextListModel[it].path == path }
        if (exists) {
            JOptionPane.showMessageDialog(
                panel,
                "File already in context: ${currentFile.name}",
                "Duplicate File",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        contextListModel.addElement(
            ContextItem(
                path = path, name = currentFile.name, startLine = 1, endLine = lineCount,
                fileType = currentFile.fileType, isSelection = false
            )
        )
    }

    private fun handleAddSelection(panel: JBPanel<JBPanel<*>>) {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val editor = fileEditorManager.selectedTextEditor
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (editor == null || currentFile == null) {
            JOptionPane.showMessageDialog(
                panel,
                "No editor is currently open",
                "No Editor",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            JOptionPane.showMessageDialog(
                panel,
                "No text is selected. Select some code first.",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

        contextListModel.addElement(
            ContextItem(
                path = currentFile.path, name = "${currentFile.name}:$startLine-$endLine",
                startLine = startLine, endLine = endLine,
                fileType = currentFile.fileType, isSelection = true
            )
        )
    }

    private fun createContextCellRenderer(): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val item = value as? ContextItem
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                if (item != null) {
                    label.icon = item.fileType?.icon ?: com.intellij.icons.AllIcons.FileTypes.Text
                    label.text = if (item.isSelection) {
                        "${item.name} (${item.endLine - item.startLine + 1} lines)"
                    } else {
                        "${item.name} (${item.endLine} lines)"
                    }
                    label.toolTipText = item.path
                    label.border = JBUI.Borders.empty(5)
                }
                return label
            }
        }
    }

    private fun createSessionTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        // Session info header
        sessionInfoLabel = JBLabel("No active session")
        sessionInfoLabel.font = JBUI.Fonts.smallFont()
        sessionInfoLabel.foreground = JBColor.GRAY
        sessionInfoLabel.border = JBUI.Borders.emptyBottom(5)
        panel.add(sessionInfoLabel, BorderLayout.NORTH)

        // Create split pane: tree on left, details on right
        val splitPane = OnePixelSplitter(false, 0.4f)

        // Left: Session content tree
        val treePanel = JBPanel<JBPanel<*>>(BorderLayout())
        treePanel.border = JBUI.Borders.empty(5)

        planRoot = javax.swing.tree.DefaultMutableTreeNode("Session")
        planTreeModel = javax.swing.tree.DefaultTreeModel(planRoot)
        val tree = com.intellij.ui.treeStructure.Tree(planTreeModel)
        tree.isRootVisible = false
        tree.showsRootHandles = true

        tree.cellRenderer = createSessionTreeRenderer()

        // Double-click or Enter opens file in editor
        fun openSelectedFile() {
            val node = tree.lastSelectedPathComponent as? FileTreeNode ?: return
            com.github.copilot.intellij.psi.EdtUtil.invokeLater {
                val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
                    node.filePath.replace("\\", "/")
                )
                if (vFile != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
                }
            }
        }
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) openSelectedFile()
            }
        })
        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) openSelectedFile()
            }
        })

        // Empty state label shown when tree has no items
        val emptyLabel = JBLabel("No session files yet")
        emptyLabel.foreground = JBColor.GRAY
        emptyLabel.horizontalAlignment = SwingConstants.CENTER

        val treeScrollPane = JBScrollPane(tree)
        val treeCardPanel = JBPanel<JBPanel<*>>(CardLayout())
        treeCardPanel.add(emptyLabel, "empty")
        treeCardPanel.add(treeScrollPane, "tree")
        treePanel.add(treeCardPanel, BorderLayout.CENTER)

        // Show empty/tree based on content
        fun updateTreeVisibility() {
            val cl = treeCardPanel.layout as CardLayout
            cl.show(treeCardPanel, if (planRoot.childCount > 0) "tree" else "empty")
        }
        planTreeModel.addTreeModelListener(object : javax.swing.event.TreeModelListener {
            override fun treeNodesChanged(e: javax.swing.event.TreeModelEvent?) = updateTreeVisibility()
            override fun treeNodesInserted(e: javax.swing.event.TreeModelEvent?) = updateTreeVisibility()
            override fun treeNodesRemoved(e: javax.swing.event.TreeModelEvent?) = updateTreeVisibility()
            override fun treeStructureChanged(e: javax.swing.event.TreeModelEvent?) = updateTreeVisibility()
        })
        updateTreeVisibility()

        splitPane.firstComponent = treePanel

        // Right: Details panel
        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        detailsPanel.border = JBUI.Borders.empty(5)

        planDetailsArea = JBTextArea()
        planDetailsArea.isEditable = false
        planDetailsArea.lineWrap = true
        planDetailsArea.wrapStyleWord = true
        planDetailsArea.text = "Select a file to preview its content.\nDouble-click or Enter to open in editor."

        val detailsScrollPane = JBScrollPane(planDetailsArea)
        detailsPanel.add(detailsScrollPane, BorderLayout.CENTER)

        tree.addTreeSelectionListener { event -> handleSessionTreeSelection(event) }

        splitPane.secondComponent = detailsPanel

        panel.add(splitPane, BorderLayout.CENTER)

        return panel
    }

    private fun createSessionTreeRenderer(): javax.swing.tree.DefaultTreeCellRenderer {
        return object : javax.swing.tree.DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val label = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? javax.swing.tree.DefaultMutableTreeNode
                val text = node?.userObject?.toString() ?: ""
                when {
                    text.contains("[completed]") -> icon = com.intellij.icons.AllIcons.Actions.Commit
                    text.contains("[in_progress]") -> icon = com.intellij.icons.AllIcons.Actions.Execute
                    text.contains("[pending]") -> icon = com.intellij.icons.AllIcons.Actions.Pause
                    text.contains("[failed]") -> icon = com.intellij.icons.AllIcons.General.Error
                }
                return label
            }
        }
    }

    private fun handleSessionTreeSelection(event: javax.swing.event.TreeSelectionEvent) {
        val node = event.path.lastPathComponent as? javax.swing.tree.DefaultMutableTreeNode
        if (node is FileTreeNode) {
            val fileLines = node.fileContent.lines()
            val preview = if (fileLines.size > 200) {
                fileLines.take(200)
                    .joinToString("\n") + "\n\n--- Truncated (${fileLines.size} lines total, showing first 200) ---"
            } else {
                node.fileContent
            }
            planDetailsArea.text = "${node.fileName}\n${"=".repeat(40)}\n\n$preview"
            planDetailsArea.caretPosition = 0
        } else {
            val text = node?.userObject?.toString() ?: ""
            planDetailsArea.text = text.ifEmpty { "Select an item to see details." }
        }
    }

    private fun createTimelineTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        // Timeline list — uses shared timelineModel populated from real events
        val timelineList = com.intellij.ui.components.JBList(timelineModel)

        // Custom cell renderer for timeline events
        timelineList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val event = value as? TimelineEvent
                val label =
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel

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

        // Bottom toolbar
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

    private fun createDebugTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

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
                        "PERMISSION_DENIED" -> JBColor.RED
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
    ): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
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
            // Client not started yet - will add listener when it starts
        }
    }

    private fun createSettingsTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Model settings section
        val modelLabel = JBLabel("<html><b>Model settings</b></html>")
        gbc.gridwidth = 2
        panel.add(modelLabel, gbc)

        gbc.gridy++
        gbc.gridwidth = 1
        panel.add(JBLabel("Default model:"), gbc)

        gbc.gridx = 1
        val settingsModelPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        val defaultModelCombo = ComboBox(arrayOf(MSG_LOADING))
        defaultModelCombo.preferredSize = JBUI.size(250, 30)
        defaultModelCombo.isEnabled = false
        settingsModelPanel.add(defaultModelCombo)
        val settingsSpinner = AsyncProcessIcon("loading-settings-models")
        settingsSpinner.preferredSize = JBUI.size(16, 16)
        settingsModelPanel.add(settingsSpinner)
        panel.add(settingsModelPanel, gbc)

        // Auth row for settings tab
        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        val settingsAuthPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        settingsAuthPanel.isVisible = false
        val settingsModelError = JBLabel()
        val settingsLoginButton = JButton("Login")
        val settingsRetryButton = JButton("Retry")
        createAuthButtons(settingsModelError, settingsLoginButton, settingsRetryButton, settingsAuthPanel)
        panel.add(settingsAuthPanel, gbc)

        // Reusable settings model loading function
        fun loadSettingsModels() {
            loadModelsAsync(
                settingsSpinner,
                defaultModelCombo,
                settingsModelError,
                settingsLoginButton,
                settingsRetryButton,
                settingsAuthPanel
            ) { }
        }

        settingsRetryButton.addActionListener { loadSettingsModels() }
        loadSettingsModels()

        // Tool permissions section
        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        val permissionsLabel = JBLabel("<html><b>Tool permissions</b></html>")
        panel.add(permissionsLabel, gbc)

        gbc.gridy++
        gbc.gridwidth = 1
        gbc.insets = JBUI.insets(5)

        val toolPermissions = listOf(
            "File Operations" to "Allow agent to read and write files",
            "Code Execution" to "Allow agent to run commands",
            "Git Operations" to "Allow agent to commit and push",
            "Network Access" to "Allow agent to make HTTP requests"
        )

        toolPermissions.forEach { (tool, description) ->
            gbc.gridx = 0
            val checkbox = JCheckBox(tool)
            checkbox.isSelected = tool == "File Operations" // Default: only file ops allowed
            checkbox.toolTipText = description
            panel.add(checkbox, gbc)

            gbc.gridy++
        }

        // Format settings section
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        val formatLabel = JBLabel("<html><b>Code formatting</b></html>")
        panel.add(formatLabel, gbc)

        gbc.gridy++
        gbc.gridwidth = 1
        gbc.insets = JBUI.insets(5)

        val formatAfterEdit = JCheckBox("Format code after agent edits")
        formatAfterEdit.isSelected = true
        panel.add(formatAfterEdit, gbc)

        gbc.gridy++
        val optimizeImports = JCheckBox("Optimize imports after edits")
        optimizeImports.isSelected = true
        panel.add(optimizeImports, gbc)

        // Agent behavior section
        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        val agentLabel = JBLabel("<html><b>Agent behavior</b></html>")
        panel.add(agentLabel, gbc)

        gbc.gridy++
        gbc.gridwidth = 1
        gbc.insets = JBUI.insets(5)
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

        // Save button
        gbc.gridy++
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        gbc.anchor = GridBagConstraints.CENTER

        val saveButton = JButton("Save Settings")
        saveButton.addActionListener {
            CopilotSettings.setPromptTimeout(timeoutSpinner.value as Int)
            CopilotSettings.setMaxToolCallsPerTurn(toolCallSpinner.value as Int)
            JOptionPane.showMessageDialog(
                panel,
                "Settings saved!",
                "Success",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        panel.add(saveButton, gbc)

        // Add filler to push everything to top
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JBPanel<JBPanel<*>>(), gbc)

        return panel
    }

    fun getComponent(): JComponent = mainPanel

    fun resetSession() {
        currentSessionId = null
        responseTextArea.text = ""
        responseTextArea.foreground = JBColor.GRAY
        appendResponse("New conversation started.\n")
        addTimelineEvent(EventType.SESSION_START, "New conversation started")
        updateSessionInfo()
        SwingUtilities.invokeLater {
            if (::planRoot.isInitialized) {
                planRoot.removeAllChildren()
                planTreeModel.reload()
                planDetailsArea.text =
                    "Session files and plan details will appear here.\n\nSelect an item in the tree to see details."
            }
        }
    }

    // Helper methods to reduce code duplication
    private fun setLoadingState(
        spinner: AsyncProcessIcon,
        comboBox: JComboBox<String>,
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel
    ) {
        SwingUtilities.invokeLater {
            spinner.isVisible = true
            authPanel.isVisible = false
            retryButton.isVisible = false
            loginButton.isVisible = false
            comboBox.removeAllItems()
            comboBox.addItem(MSG_LOADING)
            comboBox.isEnabled = false
        }
    }

    private fun isAuthenticationError(message: String): Boolean {
        return message.contains("auth") ||
            message.contains("Copilot CLI") ||
            message.contains("authenticated")
    }

    private fun populateModelComboBox(
        comboBox: JComboBox<String>,
        models: List<CopilotAcpClient.Model>,
        onSelectionChange: ((Int) -> Unit)? = null
    ) {
        comboBox.removeAllItems()
        models.forEach { model ->
            val cost = model.usage ?: "1x"
            comboBox.addItem("${model.name}  ($cost)")
        }
        // Restore persisted model selection
        val savedModel = CopilotSettings.getSelectedModel()
        if (savedModel != null) {
            val idx = models.indexOfFirst { it.id == savedModel }
            if (idx >= 0) comboBox.selectedIndex = idx
        } else if (models.isNotEmpty()) {
            comboBox.selectedIndex = 0
        }
        comboBox.isEnabled = true
        // Save selection on change
        if (onSelectionChange != null) {
            comboBox.addActionListener {
                val selIdx = comboBox.selectedIndex
                onSelectionChange(selIdx)
            }
        }
    }

    private fun showModelError(
        spinner: AsyncProcessIcon,
        comboBox: JComboBox<String>,
        errorLabel: JBLabel,
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel,
        errorMsg: String
    ) {
        val isAuthError = isAuthenticationError(errorMsg)
        val isTimeout = errorMsg.contains("timed out") || errorMsg.contains("timeout", ignoreCase = true)

        SwingUtilities.invokeLater {
            spinner.suspend()
            spinner.isVisible = false
            comboBox.removeAllItems()
            comboBox.addItem("Unavailable")
            comboBox.isEnabled = false
            errorLabel.text = when {
                isAuthError -> "⚠️ Not authenticated"
                isTimeout -> "⚠️ Connection timed out"
                else -> "⚠️ $errorMsg"
            }
            loginButton.isVisible = isAuthError
            retryButton.isVisible = !isAuthError
            authPanel.isVisible = true
        }
    }

    private fun loadModelsAsync(
        spinner: AsyncProcessIcon,
        comboBox: JComboBox<String>,
        errorLabel: JBLabel,
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel,
        onSuccess: (List<CopilotAcpClient.Model>) -> Unit
    ) {
        setLoadingState(spinner, comboBox, loginButton, retryButton, authPanel)
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Exception? = null
            val maxRetries = 3
            val retryDelayMs = 2000L

            for (attempt in 1..maxRetries) {
                lastError = attemptLoadModels(spinner, comboBox, authPanel, onSuccess)
                if (lastError == null) return@executeOnPooledThread
                if (isAuthenticationError(lastError.message ?: "")) break
                if (attempt < maxRetries) Thread.sleep(retryDelayMs)
            }

            val errorMsg = lastError?.message ?: MSG_UNKNOWN_ERROR
            showModelError(spinner, comboBox, errorLabel, loginButton, retryButton, authPanel, errorMsg)
        }
    }

    private fun attemptLoadModels(
        spinner: AsyncProcessIcon,
        comboBox: JComboBox<String>,
        authPanel: JPanel,
        onSuccess: (List<CopilotAcpClient.Model>) -> Unit
    ): Exception? {
        return try {
            val service = CopilotService.getInstance(project)
            val models = service.getClient().listModels().toList()
            SwingUtilities.invokeLater {
                spinner.isVisible = false
                populateModelComboBox(comboBox, models) { selIdx ->
                    if (selIdx >= 0 && selIdx < models.size) {
                        CopilotSettings.setSelectedModel(models[selIdx].id)
                    }
                }
                authPanel.isVisible = false
                onSuccess(models)
            }
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun createAuthButtons(
        errorLabel: JBLabel,
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel
    ) {
        errorLabel.foreground = JBColor.RED
        errorLabel.font = JBUI.Fonts.smallFont()
        authPanel.add(errorLabel)

        loginButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        loginButton.toolTipText = "Opens a terminal to authenticate with GitHub Copilot"
        loginButton.isVisible = false
        loginButton.addActionListener { startCopilotLogin() }
        authPanel.add(loginButton)

        retryButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        retryButton.toolTipText = "Retry loading models"
        retryButton.isVisible = false
        authPanel.add(retryButton)
    }

    // Data classes
    private data class ContextItem(
        val path: String,
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val fileType: com.intellij.openapi.fileTypes.FileType?,
        val isSelection: Boolean
    )

    private data class TimelineEvent(
        val type: EventType,
        val message: String,
        val timestamp: java.util.Date
    )

    private enum class EventType {
        SESSION_START,
        MESSAGE_SENT,
        RESPONSE_RECEIVED,
        ERROR,
        TOOL_CALL
    }

    /** Tree node that holds file content and path for the Plans tab. */
    private class FileTreeNode(
        val fileName: String,
        val filePath: String,
        val fileContent: String
    ) : javax.swing.tree.DefaultMutableTreeNode("📄 $fileName")
}
