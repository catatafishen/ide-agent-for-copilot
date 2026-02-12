package com.github.copilot.intellij.ui

import com.github.copilot.intellij.bridge.CopilotAcpClient
import com.github.copilot.intellij.services.CopilotService
import com.github.copilot.intellij.services.CopilotSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Main content for the Agentic Copilot tool window.
 * Uses Kotlin UI DSL for cleaner, more maintainable UI code.
 */
class AgenticCopilotToolWindowContent(private val project: Project) {

    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val tabbedPane = JBTabbedPane()
    
    // Shared context list across tabs
    private val contextListModel = DefaultListModel<ContextItem>()
    
    // Shared model list (populated from ACP)
    private var loadedModels: List<CopilotAcpClient.Model> = emptyList()
    
    // Current conversation session — reused for multi-turn
    private var currentSessionId: String? = null
    
    // Timeline events (populated from ACP session/update notifications)
    private val timelineModel = DefaultListModel<TimelineEvent>()
    
    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    
    // Usage display components (updated after each prompt)
    private lateinit var usageLabel: JBLabel
    private lateinit var costLabel: JBLabel

    init {
        setupUI()
    }

    private fun setupUI() {
        // Create tabs
        tabbedPane.addTab("Prompt", createPromptTab())
        tabbedPane.addTab("Context", createContextTab())
        tabbedPane.addTab("Plans", createPlansTab())
        tabbedPane.addTab("Timeline", createTimelineTab())
        tabbedPane.addTab("Settings", createSettingsTab())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)
    }

    /** Record an event in the Timeline tab. Thread-safe. */
    private fun addTimelineEvent(type: EventType, message: String) {
        SwingUtilities.invokeLater {
            timelineModel.addElement(TimelineEvent(type, message, java.util.Date()))
        }
    }

    /** Handle ACP session/update notifications — routes to timeline and plans. */
    private fun handleAcpUpdate(update: com.google.gson.JsonObject) {
        val updateType = update.get("sessionUpdate")?.asString ?: return
        
        when (updateType) {
            "tool_call" -> {
                val title = update.get("title")?.asString ?: "Unknown tool"
                val status = update.get("status")?.asString ?: ""
                addTimelineEvent(EventType.TOOL_CALL, "$title ($status)")
            }
            "tool_call_update" -> {
                val status = update.get("status")?.asString ?: ""
                val toolCallId = update.get("toolCallId")?.asString ?: ""
                if (status == "completed" || status == "failed") {
                    addTimelineEvent(EventType.TOOL_CALL, "Tool $toolCallId $status")
                }
            }
            "plan" -> {
                val entries = update.getAsJsonArray("entries") ?: return
                SwingUtilities.invokeLater {
                    // Replace plan tree with latest plan
                    planRoot.removeAllChildren()
                    val planNode = javax.swing.tree.DefaultMutableTreeNode("Current Plan")
                    for (entry in entries) {
                        val obj = entry.asJsonObject
                        val content = obj.get("content")?.asString ?: "Step"
                        val status = obj.get("status")?.asString ?: "pending"
                        val priority = obj.get("priority")?.asString ?: ""
                        val label = "$content [$status]${if (priority.isNotEmpty()) " ($priority)" else ""}"
                        planNode.add(javax.swing.tree.DefaultMutableTreeNode(label))
                    }
                    planRoot.add(planNode)
                    planTreeModel.reload()
                    addTimelineEvent(EventType.TOOL_CALL, "Plan updated (${entries.size()} steps)")
                }
            }
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
                    SwingUtilities.invokeLater {
                        usageLabel.text = ""
                        costLabel.text = ""
                    }
                    return@executeOnPooledThread
                }
                
                val process = ProcessBuilder(
                    ghCli, "api", "/copilot_internal/user"
                ).redirectErrorStream(true).start()
                
                val json = process.inputStream.bufferedReader().readText()
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                
                val gson = com.google.gson.Gson()
                val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java) ?: return@executeOnPooledThread
                
                val snapshots = obj.getAsJsonObject("quota_snapshots") ?: return@executeOnPooledThread
                val premium = snapshots.getAsJsonObject("premium_interactions") ?: return@executeOnPooledThread
                
                val entitlement = premium.get("entitlement")?.asInt ?: 0
                val remaining = premium.get("remaining")?.asInt ?: 0
                val unlimited = premium.get("unlimited")?.asBoolean ?: false
                val overagePermitted = premium.get("overage_permitted")?.asBoolean ?: false
                val resetDate = obj.get("quota_reset_date")?.asString ?: ""
                
                val used = entitlement - remaining
                
                SwingUtilities.invokeLater {
                    if (unlimited) {
                        usageLabel.text = "Unlimited premium requests"
                        usageLabel.toolTipText = "Resets $resetDate"
                        costLabel.text = ""
                    } else {
                        usageLabel.text = "$used / $entitlement premium requests"
                        usageLabel.toolTipText = "Resets $resetDate"
                        
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
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    usageLabel.text = ""
                    costLabel.text = ""
                }
            }
        }
    }

    /**
     * Finds the gh CLI executable, checking PATH and known install locations.
     */
    private fun findGhCli(): String? {
        // Check PATH first
        try {
            val check = ProcessBuilder("where", "gh").start()
            if (check.waitFor() == 0) return "gh"
        } catch (_: Exception) {}
        
        // Check known install locations
        val knownPaths = listOf(
            "C:\\Program Files\\GitHub CLI\\gh.exe",
            "C:\\Program Files (x86)\\GitHub CLI\\gh.exe",
            "C:\\Tools\\gh\\bin\\gh.exe",
            System.getProperty("user.home") + "\\AppData\\Local\\GitHub CLI\\gh.exe"
        )
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
                val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                var authCommand: String? = null
                var authArgs: List<String>? = null

                try {
                    val client = service.getClient()
                    val authMethod = client.authMethod
                    if (authMethod?.command != null) {
                        authCommand = authMethod.command
                        authArgs = authMethod.args
                    }
                } catch (_: Exception) {}

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
        panel.border = JBUI.Borders.empty(10)
        
        // Top toolbar with model selector and mode toggle — wraps on narrow windows
        val toolbar = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, JBUI.scale(5), JBUI.scale(5)))
        toolbar.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        
        // Model selector (placeholder shown inside dropdown)
        val modelComboBox = ComboBox(arrayOf("Loading..."))
        modelComboBox.preferredSize = JBUI.size(280, 30)
        modelComboBox.isEnabled = false
        // Custom renderer to show model name + cost
        modelComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                return this
            }
        }
        toolbar.add(modelComboBox)
        
        // Spinner shown during loading
        val loadingSpinner = AsyncProcessIcon("loading-models")
        loadingSpinner.preferredSize = JBUI.size(16, 16)
        toolbar.add(loadingSpinner)
        
        // Mode toggle (Agent / Plan)
        val modeCombo = ComboBox(arrayOf("Agent", "Plan"))
        modeCombo.preferredSize = JBUI.size(90, 30)
        modeCombo.selectedItem = if (CopilotSettings.getSessionMode() == "plan") "Plan" else "Agent"
        modeCombo.addActionListener {
            val mode = if (modeCombo.selectedItem == "Plan") "plan" else "agent"
            CopilotSettings.setSessionMode(mode)
        }
        toolbar.add(modeCombo)
        
        // Auth status panel below toolbar (hidden by default)
        val authPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        authPanel.isVisible = false
        authPanel.border = JBUI.Borders.emptyLeft(5)
        authPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        
        val modelErrorLabel = JBLabel()
        modelErrorLabel.foreground = JBColor.RED
        modelErrorLabel.font = JBUI.Fonts.smallFont()
        authPanel.add(modelErrorLabel)
        
        val loginButton = JButton("Login")
        loginButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        loginButton.toolTipText = "Opens a terminal to authenticate with GitHub Copilot"
        loginButton.isVisible = false
        loginButton.addActionListener { startCopilotLogin() }
        authPanel.add(loginButton)
        
        // Usage panel — shows real billing data from GitHub API
        val usagePanel = JBPanel<JBPanel<*>>()
        usagePanel.layout = BoxLayout(usagePanel, BoxLayout.Y_AXIS)
        usagePanel.border = JBUI.Borders.emptyLeft(10)
        usagePanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        
        usageLabel = JBLabel("")
        usageLabel.font = JBUI.Fonts.smallFont()
        usageLabel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        usagePanel.add(usageLabel)
        
        costLabel = JBLabel("")
        costLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        costLabel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        usagePanel.add(costLabel)
        
        val topPanel = JBPanel<JBPanel<*>>()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(toolbar)
        topPanel.add(authPanel)
        topPanel.add(usagePanel)
        
        panel.add(topPanel, BorderLayout.NORTH)
        
        // Fetch real billing data in background
        loadBillingData()
        
        // Center: Split pane with prompt input (top) and response output (bottom)
        val splitPane = OnePixelSplitter(true, 0.4f)
        
        // Prompt input area
        val promptPanel = JBPanel<JBPanel<*>>(BorderLayout())
        promptPanel.border = JBUI.Borders.empty(5)
        
        val promptLabel = JBLabel("Prompt:")
        promptPanel.add(promptLabel, BorderLayout.NORTH)
        
        val promptTextArea = JBTextArea()
        promptTextArea.lineWrap = true
        promptTextArea.wrapStyleWord = true
        promptTextArea.rows = 3
        
        // Response output area (declare before button listener needs it)
        val responsePanel = JBPanel<JBPanel<*>>(BorderLayout())
        responsePanel.border = JBUI.Borders.empty(5)
        
        val responseHeaderPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        val responseLabel = JBLabel("Response:")
        val responseSpinner = AsyncProcessIcon("response-loading")
        responseSpinner.preferredSize = JBUI.size(16, 16)
        responseSpinner.isVisible = false
        val responseStatus = JBLabel("")
        responseStatus.font = JBUI.Fonts.smallFont()
        responseStatus.foreground = com.intellij.ui.JBColor.GRAY
        responseHeaderPanel.add(responseLabel)
        responseHeaderPanel.add(responseSpinner)
        responseHeaderPanel.add(responseStatus)
        responsePanel.add(responseHeaderPanel, BorderLayout.NORTH)
        
        val responseTextArea = JBTextArea()
        responseTextArea.isEditable = false
        responseTextArea.lineWrap = true
        responseTextArea.wrapStyleWord = true
        responseTextArea.text = "Response will appear here after running a prompt...\n\nFirst run will auto-start the Copilot process."
        
        val responseScrollPane = JBScrollPane(responseTextArea)
        responsePanel.add(responseScrollPane, BorderLayout.CENTER)
        
        // Helper function to append to response area
        fun appendResponse(text: String) {
            SwingUtilities.invokeLater {
                responseTextArea.append(text)
                responseTextArea.caretPosition = responseTextArea.document.length
            }
        }
        
        // Helper to update response status indicator
        fun setResponseStatus(text: String, loading: Boolean = true) {
            SwingUtilities.invokeLater {
                responseStatus.text = text
                responseSpinner.isVisible = loading
            }
        }
        
        // Add document listener for token counting
        val promptScrollPane = JBScrollPane(promptTextArea)
        promptPanel.add(promptScrollPane, BorderLayout.CENTER)
        
        // Run button, Stop button, and New Chat button
        val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        val runButton = JButton("Run")
        runButton.toolTipText = "Send prompt (Enter)"
        val stopButton = JButton("Stop")
        stopButton.isVisible = false
        stopButton.toolTipText = "Cancel the current request"
        val newChatButton = JButton("New Chat")
        newChatButton.toolTipText = "Start a fresh conversation (clears session history)"
        newChatButton.addActionListener {
            currentSessionId = null
            responseTextArea.text = "New conversation started.\n"
            addTimelineEvent(EventType.SESSION_START, "New conversation started")
        }
        buttonPanel.add(runButton)
        buttonPanel.add(stopButton)
        buttonPanel.add(newChatButton)
        
        // Enter sends prompt, Shift+Enter inserts newline (standard chat UX)
        val enterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        val shiftEnterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        promptTextArea.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(enterKey, "sendPrompt")
        promptTextArea.actionMap.put("sendPrompt", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (promptTextArea.text.isNotBlank() && runButton.isEnabled) {
                    runButton.doClick()
                }
            }
        })
        promptTextArea.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(shiftEnterKey, "insertNewline")
        promptTextArea.actionMap.put("insertNewline", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                promptTextArea.insert("\n", promptTextArea.caretPosition)
            }
        })
        
        // Placeholder hint text
        promptTextArea.addFocusListener(object : java.awt.event.FocusListener {
            private val placeholder = "Ask Copilot... (Shift+Enter for new line)"
            override fun focusGained(e: java.awt.event.FocusEvent) {
                if (promptTextArea.text == placeholder) {
                    promptTextArea.text = ""
                    promptTextArea.foreground = javax.swing.UIManager.getColor("TextArea.foreground")
                }
            }
            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (promptTextArea.text.isBlank()) {
                    promptTextArea.text = placeholder
                    promptTextArea.foreground = com.intellij.ui.JBColor.GRAY
                }
            }
        })
        promptTextArea.text = "Ask Copilot... (Shift+Enter for new line)"
        promptTextArea.foreground = com.intellij.ui.JBColor.GRAY
        
        // Track current prompt thread for cancellation
        var currentPromptThread: Thread? = null
        
        stopButton.addActionListener {
            val sessionId = currentSessionId
            if (sessionId != null) {
                try {
                    val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                    service.getClient().cancelSession(sessionId)
                } catch (_: Exception) {}
            }
            currentPromptThread?.interrupt()
            appendResponse("\n⏹ Stopped by user\n")
            setResponseStatus("Stopped", loading = false)
            addTimelineEvent(EventType.ERROR, "Prompt cancelled by user")
        }
        
        runButton.addActionListener {
            val placeholderText = "Ask Copilot... (Shift+Enter for new line)"
            val prompt = promptTextArea.text.trim()
            if (prompt.isEmpty() || prompt == placeholderText) {
                return@addActionListener
            }
            
            runButton.isEnabled = false
            stopButton.isVisible = true
            setResponseStatus("Thinking...")
            
            // For first prompt or new chat, clear response area
            val isNewSession = currentSessionId == null
            if (isNewSession) {
                responseTextArea.text = ""
            }
            
            // Show the user's message in the conversation
            appendResponse("\n>>> $prompt\n\n")
            promptTextArea.text = ""
            
            // Run in background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                currentPromptThread = Thread.currentThread()
                try {
                    val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                    val client = service.getClient()
                    
                    // Reuse session for multi-turn conversation, create new if needed
                    if (currentSessionId == null) {
                        currentSessionId = client.createSession(project.basePath)
                        addTimelineEvent(EventType.SESSION_START, "Session created")
                    }
                    val sessionId = currentSessionId!!
                    
                    addTimelineEvent(EventType.MESSAGE_SENT, "Prompt: ${prompt.take(80)}${if (prompt.length > 80) "..." else ""}")
                    
                    // Get selected model from loaded models list
                    val selIdx = modelComboBox.selectedIndex
                    val selectedModelObj = if (selIdx >= 0 && selIdx < loadedModels.size) loadedModels[selIdx] else null
                    val modelId = selectedModelObj?.id ?: ""
                    // Build context references from Context tab
                    val references = mutableListOf<CopilotAcpClient.ResourceReference>()
                    for (i in 0 until contextListModel.size()) {
                        val item = contextListModel.getElementAt(i)
                        try {
                            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                .findFileByPath(item.path)
                            if (file != null) {
                                val doc = com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                                }
                                if (doc != null) {
                                    val text = if (item.isSelection && item.startLine > 0) {
                                        val startOffset = doc.getLineStartOffset(
                                            (item.startLine - 1).coerceIn(0, doc.lineCount - 1))
                                        val endOffset = doc.getLineEndOffset(
                                            (item.endLine - 1).coerceIn(0, doc.lineCount - 1))
                                        doc.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                                    } else {
                                        doc.text
                                    }
                                    val uri = "file://${item.path.replace("\\", "/")}"
                                    val mimeType = file.fileType.name.lowercase().let { ft ->
                                        when (ft) {
                                            "java" -> "text/x-java"
                                            "kotlin" -> "text/x-kotlin"
                                            "python" -> "text/x-python"
                                            "javascript" -> "text/javascript"
                                            "typescript" -> "text/typescript"
                                            "xml", "html" -> "text/$ft"
                                            else -> "text/plain"
                                        }
                                    }
                                    references.add(CopilotAcpClient.ResourceReference(uri, mimeType, text))
                                }
                            }
                        } catch (e: Exception) {
                            appendResponse("⚠ Could not read context: ${item.name}\n")
                        }
                    }
                    
                    if (references.isNotEmpty()) {
                        appendResponse("📎 ${references.size} context file(s) attached\n\n")
                    }
                    
                    // Track if we've received any content chunks
                    var receivedContent = false
                    
                    // Send prompt with context, streaming, and update handler
                    client.sendPrompt(sessionId, prompt, modelId, 
                        if (references.isNotEmpty()) references else null,
                        { chunk -> 
                            if (!receivedContent) {
                                receivedContent = true
                                setResponseStatus("Responding...")
                            }
                            appendResponse(chunk)
                        },
                        { update -> 
                            val updateType = update.get("sessionUpdate")?.asString ?: ""
                            when (updateType) {
                                "tool_call" -> {
                                    val title = update.get("title")?.asString ?: "tool"
                                    val status = update.get("status")?.asString ?: ""
                                    setResponseStatus("Running: $title")
                                    if (status != "completed") {
                                        appendResponse("🔧 $title\n")
                                    }
                                }
                                "tool_call_update" -> {
                                    val status = update.get("status")?.asString ?: ""
                                    if (status == "completed") {
                                        setResponseStatus("Thinking...")
                                    } else if (status == "failed") {
                                        val toolId = update.get("toolCallId")?.asString ?: ""
                                        appendResponse("⚠ Tool failed: $toolId\n")
                                    }
                                }
                                "agent_thought_chunk" -> {
                                    // Agent is thinking — keep spinner active
                                    if (!receivedContent) {
                                        setResponseStatus("Thinking...")
                                    }
                                }
                            }
                            handleAcpUpdate(update)
                        }
                    )
                    
                    appendResponse("\n") // clean separation after response
                    setResponseStatus("Done", loading = false)
                    addTimelineEvent(EventType.RESPONSE_RECEIVED, "Response received")
                    
                    // Refresh billing data after prompt
                    loadBillingData()
                    
                } catch (e: Exception) {
                    val msg = if (e is InterruptedException || e.cause is InterruptedException) {
                        "Request cancelled"
                    } else {
                        e.message ?: "Unknown error"
                    }
                    appendResponse("\n❌ Error: $msg\n")
                    setResponseStatus("Error", loading = false)
                    addTimelineEvent(EventType.ERROR, "Error: ${msg.take(80)}")
                    currentSessionId = null // reset session on error
                    e.printStackTrace()
                } finally {
                    currentPromptThread = null
                    SwingUtilities.invokeLater {
                        runButton.isEnabled = true
                        stopButton.isVisible = false
                    }
                }
            }
        }
        promptPanel.add(buttonPanel, BorderLayout.SOUTH)
        
        splitPane.firstComponent = promptPanel
        splitPane.secondComponent = responsePanel
        
        panel.add(splitPane, BorderLayout.CENTER)
        
        // Load models in background - fail fast on auth errors, retry only on connection errors
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Exception? = null
            val maxRetries = 3
            val retryDelayMs = 1000L
            
            for (attempt in 1..maxRetries) {
                try {
                    val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                    val client = service.getClient()
                    val models = client.listModels()
                    loadedModels = models
                    
                    SwingUtilities.invokeLater {
                        loadingSpinner.isVisible = false
                        modelComboBox.removeAllItems()
                        models.forEach { model ->
                            val cost = model.usage ?: "1x"
                            modelComboBox.addItem("${model.name}  ($cost)")
                        }
                        // Restore persisted model selection
                        val savedModel = CopilotSettings.getSelectedModel()
                        if (savedModel != null) {
                            val idx = models.indexOfFirst { it.id == savedModel }
                            if (idx >= 0) modelComboBox.selectedIndex = idx
                        } else if (models.isNotEmpty()) {
                            modelComboBox.selectedIndex = 0
                        }
                        modelComboBox.isEnabled = true
                        // Save selection on change
                        modelComboBox.addActionListener {
                            val selIdx = modelComboBox.selectedIndex
                            if (selIdx >= 0 && selIdx < loadedModels.size) {
                                CopilotSettings.setSelectedModel(loadedModels[selIdx].id)
                            }
                        }
                        authPanel.isVisible = false
                    }
                    return@executeOnPooledThread
                } catch (e: Exception) {
                    lastError = e
                    val msg = e.message ?: ""
                    val isAuthError = msg.contains("auth") || msg.contains("timed out") || 
                                     msg.contains("Copilot CLI") || msg.contains("authenticated")
                    if (isAuthError) break
                    if (attempt < maxRetries) Thread.sleep(retryDelayMs)
                }
            }
            
            val errorMsg = lastError?.message ?: "Unknown error"
            val isAuthError = errorMsg.contains("auth") || errorMsg.contains("Copilot CLI") || 
                              errorMsg.contains("authenticated") || errorMsg.contains("timed out")
            SwingUtilities.invokeLater {
                loadingSpinner.isVisible = false
                modelComboBox.removeAllItems()
                modelComboBox.addItem("Unavailable")
                modelComboBox.isEnabled = false
                modelErrorLabel.text = if (isAuthError) "⚠️ Not authenticated" else "⚠️ $errorMsg"
                loginButton.isVisible = isAuthError
                authPanel.isVisible = true
            }
        }
        
        return panel
    }

    private fun createContextTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        
        // Use class-level contextListModel
        
        // Top toolbar with Add button
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 10, 5))
        
        val addFileButton = JButton("Add Current File")
        addFileButton.toolTipText = "Add the currently open file to context"
        addFileButton.addActionListener {
            // Get current editor and file
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val currentFile = fileEditorManager.selectedFiles.firstOrNull()
            
            if (currentFile != null) {
                val virtualFile = currentFile
                val path = virtualFile.path
                val name = virtualFile.name
                val lineCount = try {
                    val editor = fileEditorManager.selectedTextEditor
                    editor?.document?.lineCount ?: 0
                } catch (e: Exception) {
                    0
                }
                
                // Create context item
                val item = ContextItem(
                    path = path,
                    name = name,
                    startLine = 1,
                    endLine = lineCount,
                    fileType = virtualFile.fileType,
                    isSelection = false
                )
                
                // Check if already added
                val exists = (0 until contextListModel.size()).any { 
                    contextListModel.get(it).path == path 
                }
                
                if (!exists) {
                    contextListModel.addElement(item)
                } else {
                    JOptionPane.showMessageDialog(
                        panel,
                        "File already in context: $name",
                        "Duplicate File",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            } else {
                JOptionPane.showMessageDialog(
                    panel,
                    "No file is currently open in the editor",
                    "No File",
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }
        toolbar.add(addFileButton)
        
        val addSelectionButton = JButton("Add Selection")
        addSelectionButton.toolTipText = "Add the current text selection to context"
        addSelectionButton.addActionListener {
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val editor = fileEditorManager.selectedTextEditor
            val currentFile = fileEditorManager.selectedFiles.firstOrNull()
            
            if (editor != null && currentFile != null) {
                val selectionModel = editor.selectionModel
                
                if (selectionModel.hasSelection()) {
                    val document = editor.document
                    val startOffset = selectionModel.selectionStart
                    val endOffset = selectionModel.selectionEnd
                    val startLine = document.getLineNumber(startOffset) + 1
                    val endLine = document.getLineNumber(endOffset) + 1
                    
                    val item = ContextItem(
                        path = currentFile.path,
                        name = "${currentFile.name}:$startLine-$endLine",
                        startLine = startLine,
                        endLine = endLine,
                        fileType = currentFile.fileType,
                        isSelection = true
                    )
                    
                    contextListModel.addElement(item)
                } else {
                    JOptionPane.showMessageDialog(
                        panel,
                        "No text is selected. Select some code first.",
                        "No Selection",
                        JOptionPane.WARNING_MESSAGE
                    )
                }
            } else {
                JOptionPane.showMessageDialog(
                    panel,
                    "No editor is currently open",
                    "No Editor",
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }
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
        
        // Context list with custom renderer
        val contextList = com.intellij.ui.components.JBList(contextListModel)
        contextList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val item = value as? ContextItem
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                
                if (item != null) {
                    // Set icon based on file type
                    label.icon = item.fileType?.icon ?: com.intellij.icons.AllIcons.FileTypes.Text
                    
                    // Format display text
                    val displayText = if (item.isSelection) {
                        "${item.name} (${item.endLine - item.startLine + 1} lines)"
                    } else {
                        "${item.name} (${item.endLine} lines)"
                    }
                    label.text = displayText
                    label.toolTipText = item.path
                    label.border = JBUI.Borders.empty(5)
                }
                
                return label
            }
        }
        
        val scrollPane = JBScrollPane(contextList)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Bottom info panel
        val infoPanel = JBPanel<JBPanel<*>>(BorderLayout())
        infoPanel.border = JBUI.Borders.empty(5)
        val infoLabel = JBLabel("Context items will be sent with each prompt")
        infoLabel.foreground = java.awt.Color.GRAY
        infoPanel.add(infoLabel, BorderLayout.WEST)
        
        val countLabel = JBLabel("0 items")
        countLabel.foreground = java.awt.Color.GRAY
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

    private fun createPlansTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        
        // Create split pane: tree on left, details on right
        val splitPane = OnePixelSplitter(false, 0.5f)
        
        // Left: Plans tree
        val treePanel = JBPanel<JBPanel<*>>(BorderLayout())
        treePanel.border = JBUI.Borders.empty(5)
        
        val treeLabel = JBLabel("Execution Plans:")
        treePanel.add(treeLabel, BorderLayout.NORTH)
        
        // Live tree — starts empty, populated from ACP plan events
        planRoot = javax.swing.tree.DefaultMutableTreeNode("Agent Plans")
        planTreeModel = javax.swing.tree.DefaultTreeModel(planRoot)
        val tree = com.intellij.ui.treeStructure.Tree(planTreeModel)
        tree.isRootVisible = true
        tree.showsRootHandles = true
        
        // Custom cell renderer for status icons
        tree.cellRenderer = object : javax.swing.tree.DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                val label = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? javax.swing.tree.DefaultMutableTreeNode
                val text = node?.userObject?.toString() ?: ""
                
                when {
                    text.contains("[completed]") -> icon = com.intellij.icons.AllIcons.Actions.Commit
                    text.contains("[in_progress]") -> icon = com.intellij.icons.AllIcons.Actions.Execute
                    text.contains("[pending]") -> icon = com.intellij.icons.AllIcons.Actions.Pause
                    text.contains("[failed]") -> icon = com.intellij.icons.AllIcons.General.Error
                    node?.parent == planRoot -> icon = com.intellij.icons.AllIcons.Nodes.Folder
                }
                
                return label
            }
        }
        
        val treeScrollPane = JBScrollPane(tree)
        treePanel.add(treeScrollPane, BorderLayout.CENTER)
        
        splitPane.firstComponent = treePanel
        
        // Right: Plan details
        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        detailsPanel.border = JBUI.Borders.empty(5)
        
        val detailsLabel = JBLabel("Plan Details:")
        detailsPanel.add(detailsLabel, BorderLayout.NORTH)
        
        planDetailsArea = JBTextArea()
        planDetailsArea.isEditable = false
        planDetailsArea.lineWrap = true
        planDetailsArea.wrapStyleWord = true
        planDetailsArea.text = "Plans will appear here when the agent creates an execution plan.\n\nUse 'Plan' mode in the Prompt tab to encourage plan-based responses."
        
        val detailsScrollPane = JBScrollPane(planDetailsArea)
        detailsPanel.add(detailsScrollPane, BorderLayout.CENTER)
        
        // Selection listener
        tree.addTreeSelectionListener { event ->
            val node = event.path.lastPathComponent as? javax.swing.tree.DefaultMutableTreeNode
            val text = node?.userObject?.toString() ?: ""
            planDetailsArea.text = text.ifEmpty { "Select a plan item to see details." }
        }
        
        splitPane.secondComponent = detailsPanel
        
        panel.add(splitPane, BorderLayout.CENTER)
        
        return panel
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
            ): java.awt.Component {
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
        val modelLabel = JBLabel("<html><b>Model Settings</b></html>")
        gbc.gridwidth = 2
        panel.add(modelLabel, gbc)
        
        gbc.gridy++
        gbc.gridwidth = 1
        panel.add(JBLabel("Default Model:"), gbc)
        
        gbc.gridx = 1
        val settingsModelPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        val defaultModelCombo = ComboBox(arrayOf("Loading..."))
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
        settingsModelError.foreground = JBColor.RED
        settingsModelError.font = JBUI.Fonts.smallFont()
        settingsAuthPanel.add(settingsModelError)
        val settingsLoginButton = JButton("Login")
        settingsLoginButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        settingsLoginButton.toolTipText = "Opens a terminal to authenticate with GitHub Copilot"
        settingsLoginButton.isVisible = false
        settingsLoginButton.addActionListener { startCopilotLogin() }
        settingsAuthPanel.add(settingsLoginButton)
        panel.add(settingsAuthPanel, gbc)
        
        // Load models from ACP - fail fast on auth errors
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Exception? = null
            val maxRetries = 3
            val retryDelayMs = 1000L
            
            for (attempt in 1..maxRetries) {
                try {
                    val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                    val client = service.getClient()
                    val models = client.listModels()
                    
                    SwingUtilities.invokeLater {
                        settingsSpinner.isVisible = false
                        defaultModelCombo.removeAllItems()
                        models.forEach { model ->
                            val cost = model.usage ?: "1x"
                            defaultModelCombo.addItem("${model.name}  ($cost)")
                        }
                        // Restore persisted selection
                        val savedModel = CopilotSettings.getSelectedModel()
                        if (savedModel != null) {
                            val idx = models.indexOfFirst { it.id == savedModel }
                            if (idx >= 0) defaultModelCombo.selectedIndex = idx
                        }
                        defaultModelCombo.isEnabled = true
                        defaultModelCombo.addActionListener {
                            val selIdx = defaultModelCombo.selectedIndex
                            if (selIdx >= 0 && selIdx < models.size) {
                                CopilotSettings.setSelectedModel(models[selIdx].id)
                            }
                        }
                        settingsAuthPanel.isVisible = false
                    }
                    return@executeOnPooledThread
                } catch (e: Exception) {
                    lastError = e
                    val msg = e.message ?: ""
                    val isAuthError = msg.contains("auth") || msg.contains("timed out") || 
                                     msg.contains("Copilot CLI") || msg.contains("authenticated")
                    if (isAuthError) break
                    if (attempt < maxRetries) Thread.sleep(retryDelayMs)
                }
            }
            
            val errorMsg = lastError?.message ?: "Unknown error"
            val isAuthError = errorMsg.contains("auth") || errorMsg.contains("Copilot CLI") || 
                              errorMsg.contains("authenticated") || errorMsg.contains("timed out")
            SwingUtilities.invokeLater {
                settingsSpinner.isVisible = false
                defaultModelCombo.removeAllItems()
                defaultModelCombo.addItem("Unavailable")
                defaultModelCombo.isEnabled = false
                settingsModelError.text = if (isAuthError) "⚠️ Not authenticated" else "⚠️ $errorMsg"
                settingsLoginButton.isVisible = isAuthError
                settingsAuthPanel.isVisible = true
            }
        }
        
        // Tool permissions section
        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        val permissionsLabel = JBLabel("<html><b>Tool Permissions</b></html>")
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
        val formatLabel = JBLabel("<html><b>Code Formatting</b></html>")
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
        
        // Save button
        gbc.gridy++
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.insets = JBUI.insets(20, 5, 5, 5)
        gbc.anchor = GridBagConstraints.CENTER
        
        val saveButton = JButton("Save Settings")
        saveButton.addActionListener {
            JOptionPane.showMessageDialog(
                panel,
                "Settings saved!\n\n(Settings persistence coming in Phase 3)",
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
}
