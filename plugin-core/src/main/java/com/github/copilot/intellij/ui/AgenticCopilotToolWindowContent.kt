package com.github.copilot.intellij.ui

import com.github.copilot.intellij.bridge.CopilotAcpClient
import com.github.copilot.intellij.services.CopilotService
import com.github.copilot.intellij.services.CopilotSettings
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Main content for the Copilot Bridge tool window.
 * Uses Kotlin UI DSL for cleaner, more maintainable UI code.
 */
class AgenticCopilotToolWindowContent(private val project: Project) {

    // UI String Constants
    private companion object {
        private val LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(AgenticCopilotToolWindowContent::class.java)
        const val MSG_LOADING = "Loading..."
        const val MSG_THINKING = "Thinking..."
        const val MSG_UNKNOWN_ERROR = "Unknown error"
        const val PROMPT_PLACEHOLDER = "Ask Copilot... (Shift+Enter for new line)"
        const val AGENT_WORK_DIR = ".agent-work"

        /** Theme-aware error color — uses IDE's error foreground or a sensible red fallback. */
        private const val OS_NAME_PROPERTY = "os.name"
        private val ERROR_COLOR: JBColor
            get() = UIManager.getColor("Label.errorForeground") as? JBColor
                ?: JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x60, 0x60))
    }

    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

    // Shared context list across tabs
    private val contextListModel = DefaultListModel<ContextItem>()

    // Shared model list (populated from ACP)
    private var loadedModels: List<CopilotAcpClient.Model> = emptyList()

    // Current conversation session — reused for multi-turn
    private var currentSessionId: String? = null

    // Prompt tab fields (promoted from local variables for footer layout)
    private var selectedModelIndex = -1
    private var modelsStatusText: String? = MSG_LOADING
    private lateinit var controlsToolbar: ActionToolbar
    private lateinit var promptTextArea: EditorTextField
    private lateinit var loadingSpinner: AsyncProcessIcon
    private var currentPromptThread: Thread? = null
    private var isSending = false
    private lateinit var processingTimerPanel: ProcessingTimerPanel
    private lateinit var attachmentsPanel: JBPanel<JBPanel<*>>

    // Timeline events (populated from ACP session/update notifications)
    private val timelineModel = DefaultListModel<TimelineEvent>()
    private val debugPanel = DebugPanel(project, timelineModel)

    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    private lateinit var sessionInfoLabel: JBLabel

    // Billing/usage management (extracted to BillingManager)
    private val billing = BillingManager()
    private lateinit var consolePanel: ChatConsolePanel

    // Per-turn tracking
    private var turnToolCallCount = 0
    private var turnModelId = ""

    private var conversationSummaryInjected = false

    init {
        setupUI()
        restoreConversation()
    }

    private fun setupUI() {
        mainPanel.add(createPromptTab(), BorderLayout.CENTER)
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
    private val toolCallTitles = mutableMapOf<String, String>() // toolCallId -> display title

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
                        if (!::planRoot.isInitialized) return@invokeLater
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
                    val terminalHint = if (System.getProperty(OS_NAME_PROPERTY).lowercase().contains("win"))
                        "Command Prompt or PowerShell" else "your terminal"
                    Messages.showErrorDialog(
                        project,
                        "Could not start the authentication flow automatically.\n\n" +
                            "To authenticate manually:\n" +
                            "1. Open $terminalHint\n" +
                            "2. Run: copilot auth login\n" +
                            "3. Follow the browser prompts\n" +
                            "4. Click 'Retry' in this plugin\n\n" +
                            "Technical details: ${e.message}",
                        "Authentication Setup"
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

        // Response/chat history area (top of splitter)
        val responsePanel = createResponsePanel()
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        topPanel.add(authPanel, BorderLayout.NORTH)
        topPanel.add(responsePanel, BorderLayout.CENTER)

        // Input row (bottom of splitter — resizable)
        val inputRow = createInputRow()

        // Splitter between output and input only
        val splitter = OnePixelSplitter(true, 0.75f)
        splitter.firstComponent = topPanel
        splitter.secondComponent = inputRow
        splitter.setHonorComponentsMinimumSize(true)
        panel.add(splitter, BorderLayout.CENTER)

        // Fixed footer: controls + usage (not resized by splitter)
        loadingSpinner = AsyncProcessIcon("loading-models")
        loadingSpinner.preferredSize = JBUI.size(16, 16)
        val fixedFooter = createFixedFooter()
        panel.add(fixedFooter, BorderLayout.SOUTH)

        billing.loadBillingData()

        // Load models
        fun loadModels() {
            loadModelsAsync(
                loadingSpinner,
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

    private fun createFixedFooter(): JBPanel<JBPanel<*>> {
        val footer = JBPanel<JBPanel<*>>()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.border = JBUI.Borders.compound(
            com.intellij.ui.SideBorder(JBColor.border(), com.intellij.ui.SideBorder.TOP),
            JBUI.Borders.empty(0, 0, 2, 0)
        )

        // Single row: controls + usage (wraps on narrow windows)
        val controlsRow = createControlsRow()
        controlsRow.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(controlsRow)

        return footer
    }

    private fun createInputRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        row.minimumSize = JBUI.size(100, 40)

        // Attachments chip panel (shown above input when files attached)
        attachmentsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2))
        attachmentsPanel.isOpaque = false
        attachmentsPanel.isVisible = false
        attachmentsPanel.border = JBUI.Borders.emptyBottom(2)

        // Use EditorTextFieldProvider for PsiFile-backed document (enables spell checking)
        val editorCustomizations = mutableListOf<com.intellij.ui.EditorCustomization>()
        try {
            val spellCheck = com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
                .getInstance().getEnabledCustomization()
            if (spellCheck != null) editorCustomizations.add(spellCheck)
        } catch (_: Exception) {
            // Spellchecker plugin not available
        }
        promptTextArea = com.intellij.ui.EditorTextFieldProvider.getInstance()
            .getEditorField(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE, project, editorCustomizations)
        promptTextArea.setOneLineMode(false)
        promptTextArea.border = null

        // Drag-drop works on the EditorTextField wrapper (no editor needed)
        setupPromptDragDrop(promptTextArea)
        // Key bindings and context menu need the editor's content component.
        // addSettingsProvider runs when the editor is actually created,
        // unlike invokeLater which may fire before the editor exists.
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(promptTextArea, editor)
            setupPromptContextMenu(editor)
            // Use EditorEx built-in placeholder (visual-only, doesn't set actual text)
            editor.setPlaceholder(PROMPT_PLACEHOLDER)
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps = true
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
            editor.setBorder(null)
        }

        // Auto-revalidate on document changes
        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                SwingUtilities.invokeLater { promptTextArea.revalidate() }
            }
        })

        val inputWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        inputWrapper.add(attachmentsPanel, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(promptTextArea)
        scrollPane.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.border = null
        scrollPane.viewportBorder = null
        inputWrapper.add(scrollPane, BorderLayout.CENTER)
        row.border = JBUI.Borders.empty()
        row.add(inputWrapper, BorderLayout.CENTER)

        // Refresh attachment chips when list changes
        contextListModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
        })

        return row
    }

    private fun refreshAttachmentChips() {
        SwingUtilities.invokeLater {
            attachmentsPanel.removeAll()
            for (i in 0 until contextListModel.size()) {
                val item = contextListModel.getElementAt(i)
                val chip = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 2, 0))
                chip.isOpaque = true
                chip.background = UIManager.getColor("ActionButton.hoverBackground")
                    ?: JBColor(Color(0xDF, 0xE1, 0xE5), Color(0x35, 0x3B, 0x48))
                chip.border = JBUI.Borders.empty(1, 6, 1, 2)
                val icon = if (item.isSelection) "\u2702" else "\uD83D\uDCC4"
                val label = JBLabel("$icon ${item.name}")
                label.font = JBUI.Fonts.smallFont()
                chip.add(label)
                val removeBtn = JBLabel("\u2715")
                removeBtn.font = JBUI.Fonts.smallFont()
                removeBtn.foreground = JBColor.GRAY
                removeBtn.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                removeBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        val idx = (0 until contextListModel.size()).firstOrNull { contextListModel[it] === item }
                        if (idx != null) contextListModel.remove(idx)
                    }
                })
                chip.add(removeBtn)
                attachmentsPanel.add(chip)
            }
            attachmentsPanel.isVisible = contextListModel.size() > 0
            attachmentsPanel.revalidate()
            attachmentsPanel.repaint()
        }
    }

    private fun onSendStopClicked() {
        if (isSending) {
            handleStopRequest(currentPromptThread)
            setSendingState(false)
        } else {
            val prompt = promptTextArea.text.trim()
            if (prompt.isEmpty()) return
            consolePanel.disableQuickReplies()
            setSendingState(true)
            setResponseStatus(MSG_THINKING)

            // Add session separator before new prompt if old content exists and no active session
            if (currentSessionId == null && consolePanel.hasContent()) {
                val ts = java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(java.util.Date())
                consolePanel.addSessionSeparator(ts)
            }

            val ctxFiles = if (contextListModel.size() > 0) {
                (0 until contextListModel.size()).map { i ->
                    val item = contextListModel.getElementAt(i)
                    Triple(item.name, item.path, if (item.isSelection) item.startLine else 0)
                }
            } else null
            consolePanel.addPromptEntry(prompt, ctxFiles)
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
            controlsToolbar.updateActionsAsync()
            if (::processingTimerPanel.isInitialized) {
                if (sending) processingTimerPanel.start() else processingTimerPanel.stop()
            }
        }
    }

    private fun createControlsRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())

        // Left toolbar — grouped logically:
        // [Send] | [Attach File, Attach Selection] | [Model, Mode] | [Follow, Format, Build, Test, Commit] | [Project Files ▾] | [Export, Help]
        val leftGroup = DefaultActionGroup()
        leftGroup.add(SendStopAction())
        leftGroup.addSeparator()
        leftGroup.add(AttachFileAction())
        leftGroup.add(AttachSelectionAction())
        leftGroup.addSeparator()
        leftGroup.add(ModelSelectorAction())
        leftGroup.add(ModeSelectorAction())
        leftGroup.addSeparator()
        leftGroup.add(FollowAgentFilesToggleAction())
        leftGroup.add(FormatAfterEditToggleAction())
        leftGroup.add(BuildBeforeEndToggleAction())
        leftGroup.add(TestBeforeEndToggleAction())
        leftGroup.add(CommitBeforeEndToggleAction())
        leftGroup.addSeparator()
        leftGroup.add(ProjectFilesDropdownAction())
        leftGroup.addSeparator()
        leftGroup.add(CopyConversationAction())
        leftGroup.add(HelpAction(project))

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.setReservePlaceAutoPopupIcon(false)

        // Right toolbar: processing indicator + usage graph (always right-aligned)
        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        rightGroup.add(billing.UsageGraphAction())

        val usageToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotUsage", rightGroup, true
        )
        usageToolbar.targetComponent = row
        usageToolbar.setReservePlaceAutoPopupIcon(false)

        row.add(controlsToolbar.component, BorderLayout.CENTER)
        row.add(usageToolbar.component, BorderLayout.EAST)

        return row
    }

    /** Toolbar action showing a native processing timer while the agent works */
    private inner class ProcessingIndicatorAction : AnAction("Processing"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) { /* No action needed — UI-only toolbar widget */
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            processingTimerPanel = ProcessingTimerPanel()
            return processingTimerPanel
        }
    }

    /**
     * Native Swing panel that shows a small animated spinner + elapsed-time counter
     * plus tool call count and requests used. Hidden when idle; visible once [start] is called.
     * On [stop], spinner changes to checkmark and stats remain visible until next [start].
     * Click to toggle between per-turn and session-wide stats.
     */
    private inner class ProcessingTimerPanel : JBPanel<ProcessingTimerPanel>() {
        private val spinner = AsyncProcessIcon("CopilotProcessing")
        private val doneIcon = JBLabel("\u2705")
        private val timerLabel = JBLabel("")
        private val toolsLabel = JBLabel("")
        private val requestsLabel = JBLabel("")
        private var startedAt = 0L
        private var toolCallCount = 0
        private var requestsUsed = 0
        private val ticker = javax.swing.Timer(1000) { refreshDisplay() }

        // Session-wide accumulators
        private var sessionTotalTimeMs = 0L
        private var sessionTotalToolCalls = 0
        private var sessionTotalRequests = 0
        private var sessionTurnCount = 0
        private var isRunning = false

        private val modeTurn = 0
        private val modeSession = 1
        private var displayMode = modeTurn

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyRight(6)
            val smallGray = JBUI.Fonts.smallFont()
            spinner.isVisible = false
            doneIcon.isVisible = false
            doneIcon.font = smallGray
            timerLabel.foreground = JBColor.GRAY; timerLabel.font = smallGray; timerLabel.isVisible = false
            toolsLabel.foreground = JBColor.GRAY; toolsLabel.font = smallGray; toolsLabel.isVisible = false
            requestsLabel.foreground = JBColor.GRAY; requestsLabel.font = smallGray; requestsLabel.isVisible = false
            add(Box.createHorizontalGlue())
            add(spinner)
            add(Box.createHorizontalStrut(4))
            add(doneIcon)
            add(Box.createHorizontalStrut(4))
            add(timerLabel)
            add(Box.createHorizontalStrut(4))
            add(toolsLabel)
            add(Box.createHorizontalStrut(4))
            add(requestsLabel)
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to toggle turn/session stats"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    displayMode = if (displayMode == modeTurn) modeSession else modeTurn
                    refreshDisplay()
                }
            })
        }

        fun start() {
            startedAt = System.currentTimeMillis()
            toolCallCount = 0
            requestsUsed = 0
            isRunning = true
            displayMode = modeTurn
            timerLabel.text = "0s"
            toolsLabel.text = ""
            requestsLabel.text = ""
            spinner.isVisible = true
            spinner.resume()
            doneIcon.isVisible = false
            timerLabel.isVisible = true
            toolsLabel.isVisible = false
            requestsLabel.isVisible = false
            isVisible = true
            ticker.start()
            revalidate(); repaint()
        }

        fun stop() {
            ticker.stop()
            isRunning = false
            // Accumulate into session totals
            sessionTotalTimeMs += System.currentTimeMillis() - startedAt
            sessionTotalToolCalls += toolCallCount
            sessionTotalRequests += requestsUsed
            sessionTurnCount++
            refreshDisplay()
            spinner.suspend()
            spinner.isVisible = false
            doneIcon.isVisible = true
            revalidate(); repaint()
        }

        fun resetSession() {
            sessionTotalTimeMs = 0L
            sessionTotalToolCalls = 0
            sessionTotalRequests = 0
            sessionTurnCount = 0
            displayMode = modeTurn
        }

        fun incrementToolCalls() {
            toolCallCount++
            refreshDisplay()
        }

        fun setRequestsUsed(count: Int) {
            requestsUsed = count
            refreshDisplay()
        }

        fun incrementRequests(multiplier: Int = 1) {
            SwingUtilities.invokeLater {
                requestsUsed += multiplier
            }
            refreshDisplay()
        }

        private fun refreshDisplay() {
            SwingUtilities.invokeLater {
                when (displayMode) {
                    modeTurn -> refreshTurnMode()
                    modeSession -> refreshSessionMode()
                }
                revalidate(); repaint()
            }
        }

        private fun refreshTurnMode() {
            toolTipText = "Click to show session stats"
            updateLabel()
            toolsLabel.text = if (toolCallCount > 0) "\u2022 $toolCallCount tools" else ""
            toolsLabel.isVisible = toolCallCount > 0
            requestsLabel.text = if (requestsUsed > 0) "\u2022 $requestsUsed req" else ""
            requestsLabel.isVisible = requestsUsed > 0
            if (!isRunning) doneIcon.text = "\u2705"
        }

        private fun refreshSessionMode() {
            toolTipText = "Click to show turn stats"
            val totalMs =
                sessionTotalTimeMs + if (isRunning) (System.currentTimeMillis() - startedAt) else 0
            val totalSec = totalMs / 1000
            timerLabel.text = if (totalSec < 60) "${totalSec}s" else "${totalSec / 60}m ${totalSec % 60}s"
            val totalTools = sessionTotalToolCalls + if (isRunning) toolCallCount else 0
            toolsLabel.text = if (totalTools > 0) "\u2022 $totalTools tools" else ""
            toolsLabel.isVisible = totalTools > 0
            val billingReqs =
                if (billing.billingCycleStartUsed >= 0 && billing.lastBillingUsed > billing.billingCycleStartUsed)
                    billing.lastBillingUsed - billing.billingCycleStartUsed else -1
            val totalReqs = sessionTotalRequests + if (isRunning) requestsUsed else 0
            requestsLabel.text = when {
                billingReqs > 0 -> "\u2022 $billingReqs req"
                totalReqs > 0 -> "\u2022 ~$totalReqs req"
                else -> ""
            }
            requestsLabel.isVisible = requestsLabel.text.isNotEmpty()
            doneIcon.text = "\u2211"
        }

        private fun updateLabel() {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            timerLabel.text = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
        }
    }

    // Send/Stop toggle action for the toolbar
    private inner class SendStopAction : AnAction(
        "Send", "Send prompt (Enter)", com.intellij.icons.AllIcons.Actions.Execute
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            onSendStopClicked()
        }

        override fun update(e: AnActionEvent) {
            if (isSending) {
                e.presentation.icon = com.intellij.icons.AllIcons.Actions.Suspend
                e.presentation.text = "Stop"
                e.presentation.description = "Stop"
            } else {
                e.presentation.icon = com.intellij.icons.AllIcons.Actions.Execute
                e.presentation.text = "Send"
                e.presentation.description = "Send prompt (Enter)"
            }
        }
    }

    // Attach current file to the next prompt
    private inner class AttachFileAction : AnAction(
        "Attach File", "Attach current file to prompt", com.intellij.icons.AllIcons.Actions.AddFile
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            handleAddCurrentFile()
        }
    }

    // Attach current selection to the next prompt
    private inner class AttachSelectionAction : AnAction(
        "Attach Selection", "Attach selected text to prompt", com.intellij.icons.AllIcons.Actions.AddMulticaret
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            handleAddSelection()
        }
    }

    // Export conversation to clipboard (popup with Text / HTML options)
    private inner class CopyConversationAction : AnAction(
        "Export Chat", "Export conversation to clipboard", com.intellij.icons.AllIcons.ToolbarDecorator.Export
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup()
            group.add(object : AnAction("Copy as Text") {
                override fun actionPerformed(e: AnActionEvent) {
                    val text = consolePanel.getConversationText()
                    if (text.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            group.add(object : AnAction("Copy as HTML") {
                override fun actionPerformed(e: AnActionEvent) {
                    val html = consolePanel.getConversationHtml()
                    if (html.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(html), null)
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            val popup = ActionManager.getInstance()
                .createActionPopupMenu(ActionPlaces.TOOLWINDOW_CONTENT, group)
            val comp = e.inputEvent?.component ?: return
            popup.component.show(comp, 0, comp.height)
        }
    }

    private inner class FollowAgentFilesToggleAction : ToggleAction(
        "Auto-open files as agent works",
        "Instruct the agent to auto-open files in the editor as it reads or writes them",
        com.intellij.icons.AllIcons.Actions.Preview
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            return com.github.copilot.intellij.services.CopilotSettings.getFollowAgentFiles()
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            com.github.copilot.intellij.services.CopilotSettings.setFollowAgentFiles(state)
        }
    }

    private inner class FormatAfterEditToggleAction : ToggleAction(
        "Agent: auto-format after edits", "Instruct the agent to auto-format code after editing files",
        com.intellij.icons.AllIcons.Actions.ReformatCode
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            return CopilotSettings.getFormatAfterEdit()
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CopilotSettings.setFormatAfterEdit(state)
        }
    }

    private inner class BuildBeforeEndToggleAction : ToggleAction(
        "Agent: build before completing", "Instruct the agent to build the project before completing its turn",
        com.intellij.icons.AllIcons.Actions.Compile
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            return CopilotSettings.getBuildBeforeEnd()
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CopilotSettings.setBuildBeforeEnd(state)
        }
    }

    private inner class TestBeforeEndToggleAction : ToggleAction(
        "Agent: run tests before completing", "Instruct the agent to run tests before completing its turn",
        com.intellij.icons.AllIcons.Nodes.Test
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            return CopilotSettings.getTestBeforeEnd()
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CopilotSettings.setTestBeforeEnd(state)
        }
    }

    private inner class CommitBeforeEndToggleAction : ToggleAction(
        "Agent: auto-commit before completing", "Instruct the agent to auto-commit changes before completing its turn",
        com.intellij.icons.AllIcons.Actions.Commit
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            return CopilotSettings.getCommitBeforeEnd()
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CopilotSettings.setCommitBeforeEnd(state)
        }
    }

    // HelpAction extracted to HelpDialog.kt

    /** Open a project-root file in the editor if it exists */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Open a file in the editor, creating it with default content if it doesn't exist yet. */
    private fun openOrCreateProjectFile(relativePath: String, defaultContent: () -> String) {
        val base = project.basePath ?: return
        val file = java.io.File(base, relativePath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(defaultContent())
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(file.absolutePath) ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Load the bundled default MCP instructions from plugin resources. */
    private fun loadDefaultMcpInstructions(): String {
        val stream = javaClass.getResourceAsStream("/default-mcp-instructions.md")
        return stream?.bufferedReader()?.use { it.readText() }
            ?: "You are running inside an IntelliJ IDEA plugin with IDE tools."
    }

    /** Dropdown action for project configuration files: Instructions, TODO, Agent Definitions, MCP Instructions */
    private inner class ProjectFilesDropdownAction : AnAction(
        "Project Files", "Open project configuration files",
        com.intellij.icons.AllIcons.Nodes.Folder
    ), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val inputEvent = e.inputEvent ?: return
            val component = inputEvent.source as? java.awt.Component ?: return
            showPopup(component)
        }

        override fun createCustomComponent(
            presentation: com.intellij.openapi.actionSystem.Presentation,
            place: String
        ): javax.swing.JComponent {
            val button = com.intellij.openapi.actionSystem.impl.ActionButtonWithText(
                this,
                presentation,
                place,
                com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
            )
            return button
        }

        private fun showPopup(owner: java.awt.Component) {
            val group = DefaultActionGroup()
            val base = project.basePath

            // Instructions
            group.add(object : AnAction(
                "Instructions",
                "Open copilot-instructions.md",
                com.intellij.icons.AllIcons.Actions.IntentionBulb
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = base != null && java.io.File(base, "copilot-instructions.md").exists()
                }

                override fun actionPerformed(e: AnActionEvent) = openProjectFile("copilot-instructions.md")
            })

            // TODO
            group.add(object : AnAction("TODO", "Open TODO.md", com.intellij.icons.AllIcons.General.TodoDefault) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = base != null && java.io.File(base, "TODO.md").exists()
                }

                override fun actionPerformed(e: AnActionEvent) = openProjectFile("TODO.md")
            })

            group.addSeparator("Agent Definitions")

            // Agent definition files from .github/agents/
            val agentsDir = if (base != null) java.io.File(base, ".github/agents") else null
            val agentFiles = agentsDir?.listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name } ?: emptyList()
            if (agentFiles.isNotEmpty()) {
                for (agentFile in agentFiles) {
                    val name = agentFile.nameWithoutExtension
                    group.add(object :
                        AnAction(name, "Open ${agentFile.name}", com.intellij.icons.AllIcons.General.User) {
                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                        override fun actionPerformed(e: AnActionEvent) =
                            openProjectFile(".github/agents/${agentFile.name}")
                    })
                }
            } else {
                group.add(object : AnAction("No agents defined", null, null) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }

                    override fun actionPerformed(e: AnActionEvent) { /* no-op: placeholder for disabled menu item */
                    }
                })
            }

            group.addSeparator("MCP Server")

            // Startup Instructions (MCP server instructions)
            group.add(object : AnAction(
                "Startup Instructions",
                "Open .agent-work/startup-instructions.md",
                com.intellij.icons.AllIcons.Actions.IntentionBulbGrey
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    openOrCreateProjectFile(".agent-work/startup-instructions.md") {
                        loadDefaultMcpInstructions()
                    }
                }
            })

            // Restore default
            group.add(object : AnAction(
                "Restore Default Instructions",
                "Reset startup instructions to built-in default",
                com.intellij.icons.AllIcons.Actions.Rollback
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    val filePath = ".agent-work/startup-instructions.md"
                    val absFile = java.io.File(base ?: return, filePath)
                    absFile.parentFile?.mkdirs()
                    absFile.writeText(loadDefaultMcpInstructions())
                    // Refresh and open
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(absFile.absolutePath) ?: return
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
                }
            })

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )
            popup.showUnderneathOf(owner)
        }
    }

    // ComboBoxAction for model selection — matches Run panel dropdown style
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            loadedModels.forEachIndexed { index, model ->
                val cost = model.usage ?: "1x"
                group.add(object : AnAction("${model.name}  ($cost)") {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (index == selectedModelIndex) return

                        selectedModelIndex = index
                        CopilotSettings.setSelectedModel(model.id)
                        LOG.info("Model selected: ${model.id} (index=$index)")
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val client = CopilotService.getInstance(project).getClient()
                                val sessionId = currentSessionId
                                if (sessionId != null) {
                                    // Switch model on current session (no restart needed)
                                    client.setModel(sessionId, model.id)
                                    LOG.info("Model switched to ${model.id} on session $sessionId")
                                } else {
                                    LOG.info("No active session; model ${model.id} will be used on next session")
                                }
                            } catch (ex: Exception) {
                                LOG.warn("Failed to set model ${model.id} via session/set_model", ex)
                            }
                        }
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            val text = modelsStatusText
                ?: loadedModels.getOrNull(selectedModelIndex)?.name
                ?: MSG_LOADING
            e.presentation.text = text
            e.presentation.isEnabled = modelsStatusText == null && loadedModels.isNotEmpty()
        }
    }

    // ComboBoxAction for mode selection — matches Run panel dropdown style
    private class ModeSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            group.add(object : AnAction("Agent") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopilotSettings.setSessionMode("agent")
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            group.add(object : AnAction("Plan") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopilotSettings.setSessionMode("plan")
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            return group
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = if (CopilotSettings.getSessionMode() == "plan") "Plan" else "Agent"
        }
    }

    private fun createResponsePanel(): JComponent {
        consolePanel = ChatConsolePanel(project)
        consolePanel.onQuickReply = { text -> SwingUtilities.invokeLater { sendQuickReply(text) } }
        // Register for proper JCEF browser disposal
        com.intellij.openapi.util.Disposer.register(project, consolePanel)
        // Placeholder only shown if no conversation is restored (set after restore check)
        return consolePanel
    }

    private fun appendResponse(text: String) {
        consolePanel.appendText(text)
    }

    @Suppress("unused")
    private fun setResponseStatus(text: String, loading: Boolean = true) {
        // Status indicator removed from UI \u2192 kept as no-op to avoid call-site churn
    }

    private fun setupPromptKeyBindings(promptTextArea: EditorTextField, editor: EditorEx) {
        val contentComponent = editor.contentComponent

        // Use IntelliJ's action system (not Swing InputMap) so the shortcut takes priority
        // over the editor's built-in Enter handler (ACTION_EDITOR_ENTER).
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isNotBlank() && !isSending) {
                    onSendStopClicked()
                }
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)),
            contentComponent
        )

        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val offset = editor.caretModel.offset
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.insertString(offset, "\n")
                }
                editor.caretModel.moveToOffset(offset + 1)
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun setupPromptContextMenu(editor: EditorEx) {
        // Build a combined action group: native editor menu + our custom items
        val group = DefaultActionGroup().apply {
            // Include the standard editor popup menu (Cut, Copy, Paste, Select All, etc.)
            val editorPopup = ActionManager.getInstance().getAction("EditorPopupMenu")
            if (editorPopup != null) {
                add(editorPopup)
            }

            addSeparator()

            // Attach actions
            add(object : AnAction("Attach Current File", null, com.intellij.icons.AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) = handleAddCurrentFile()
            })
            add(object : AnAction("Attach Editor Selection", null, com.intellij.icons.AllIcons.Actions.AddMulticaret) {
                override fun actionPerformed(e: AnActionEvent) = handleAddSelection()
            })
            add(object : AnAction("Clear Attachments", null, com.intellij.icons.AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    contextListModel.clear()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = contextListModel.size() > 0
                }
            })

            addSeparator()

            // Conversation actions
            add(object : AnAction("New Conversation", null, com.intellij.icons.AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    currentSessionId = null
                    consolePanel.addSessionSeparator(
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    )
                    updateSessionInfo()
                }
            })
        }

        // Install via the IntelliJ editor popup handler (replaces the default EditorPopupMenu with our combined group)
        editor.installPopupHandler(
            com.intellij.openapi.editor.impl.ContextMenuPopupHandler.Simple(group)
        )
    }

    private fun setupPromptDragDrop(textArea: EditorTextField) {
        textArea.dropTarget = java.awt.dnd.DropTarget(
            textArea, java.awt.dnd.DnDConstants.ACTION_COPY,
            object : java.awt.dnd.DropTargetAdapter() {
                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    try {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                        val transferable = dtde.transferable
                        if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(
                                java.awt.datatransfer.DataFlavor.javaFileListFlavor
                            ) as List<java.io.File>
                            for (file in files) {
                                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                    .findFileByIoFile(file) ?: continue
                                val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                    .getDocument(vf) ?: continue
                                val exists = (0 until contextListModel.size()).any {
                                    contextListModel[it].path == vf.path
                                }
                                if (!exists) {
                                    contextListModel.addElement(
                                        ContextItem(
                                            path = vf.path, name = vf.name,
                                            startLine = 1, endLine = doc.lineCount,
                                            fileType = vf.fileType, isSelection = false
                                        )
                                    )
                                }
                            }
                            dtde.dropComplete(true)
                        } else {
                            dtde.dropComplete(false)
                        }
                    } catch (_: Exception) {
                        dtde.dropComplete(false)
                    }
                }
            })
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
        consolePanel.addErrorEntry("Stopped by user")
        setResponseStatus("Stopped", loading = false)
        addTimelineEvent(EventType.ERROR, "Prompt cancelled by user")
    }

    private fun ensureSessionCreated(client: CopilotAcpClient): String {
        if (currentSessionId == null) {
            currentSessionId = client.createSession(project.basePath)
            addTimelineEvent(EventType.SESSION_START, "Session created")
            updateSessionInfo()
            val savedModel = CopilotSettings.getSelectedModel()
            if (!savedModel.isNullOrEmpty()) {
                try {
                    client.setModel(currentSessionId!!, savedModel)
                } catch (ex: Exception) {
                    LOG.warn("Failed to set model $savedModel on new session", ex)
                }
            }
        }
        return currentSessionId!!
    }

    private fun buildEffectivePrompt(prompt: String): String {
        val snippetSuffix = buildSnippetSuffix()
        var effective = if (snippetSuffix.isNotEmpty()) "$prompt\n\n$snippetSuffix" else prompt

        if (CopilotSettings.getSessionMode() == "plan") {
            effective = "[[PLAN]] $effective"
        }

        val toggleSuffix = buildToggleSuffix()
        if (toggleSuffix.isNotEmpty()) {
            effective = "$effective\n\n$toggleSuffix"
        }

        if (!conversationSummaryInjected) {
            conversationSummaryInjected = true
            val summary = consolePanel.getCompressedSummary()
            if (summary.isNotEmpty()) {
                effective = "$summary\n\n$effective"
            }
        }
        return effective
    }

    private fun handlePromptCompletion(prompt: String) {
        com.github.copilot.intellij.psi.PsiBridgeService.getInstance(project).flushPendingAutoFormat()
        consolePanel.finishResponse(turnToolCallCount, turnModelId, getModelMultiplier(turnModelId))
        notifyIfUnfocused(turnToolCallCount)
        setResponseStatus("Done", loading = false)
        addTimelineEvent(EventType.RESPONSE_RECEIVED, "Response received")
        saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
        saveConversation()
        billing.loadBillingData()

        val lastResponse = consolePanel.getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            SwingUtilities.invokeLater { consolePanel.showQuickReplies(quickReplies) }
        }
    }

    private fun executePrompt(prompt: String) {
        try {
            val service = CopilotService.getInstance(project)
            val client = service.getClient()
            val sessionId = ensureSessionCreated(client)

            addTimelineEvent(
                EventType.MESSAGE_SENT,
                "Prompt: ${prompt.take(80)}${if (prompt.length > 80) "..." else ""}"
            )

            val selectedModelObj =
                if (selectedModelIndex >= 0 && selectedModelIndex < loadedModels.size) loadedModels[selectedModelIndex] else null
            val modelId = selectedModelObj?.id ?: ""
            turnToolCallCount = 0
            turnModelId = modelId

            SwingUtilities.invokeLater {
                consolePanel.setPromptStats(modelId, getModelMultiplier(modelId))
            }

            val references = buildContextReferences()
            val effectivePrompt = buildEffectivePrompt(prompt)

            if (references.isNotEmpty()) {
                val contextFiles = (0 until contextListModel.size()).map { i ->
                    val item = contextListModel.getElementAt(i)
                    Pair(item.name, item.path)
                }
                consolePanel.addContextFilesEntry(contextFiles)
            }
            SwingUtilities.invokeLater { contextListModel.clear() }

            var receivedContent = false
            client.sendPrompt(
                sessionId, effectivePrompt, modelId,
                references.ifEmpty { null },
                { chunk ->
                    if (!receivedContent) {
                        receivedContent = true
                        setResponseStatus("Responding...")
                    }
                    appendResponse(chunk)
                },
                { update -> handlePromptStreamingUpdate(update, receivedContent) },
                {
                    val mult = getModelMultiplier(modelId).removeSuffix("x").toIntOrNull() ?: 1
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementRequests(mult)
                }
            )

            handlePromptCompletion(prompt)
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

    /** Build a suffix telling the agent which completion-step toggles are enabled. */
    private fun buildToggleSuffix(): String {
        val steps = mutableListOf<String>()
        if (CopilotSettings.getBuildBeforeEnd()) steps.add("build the project (build_project)")
        if (CopilotSettings.getTestBeforeEnd()) steps.add("run tests (run_tests)")
        if (CopilotSettings.getCommitBeforeEnd()) steps.add("commit your changes (git_stage + git_commit)")
        if (steps.isEmpty()) return ""
        return "Before completing your turn, you MUST: ${steps.joinToString(", ")}."
    }

    /** Send a quick-reply as if the user typed it. Called from the JS bridge on EDT. */
    private fun sendQuickReply(text: String) {
        if (isSending) return
        consolePanel.disableQuickReplies()
        promptTextArea.text = text
        onSendStopClicked()
    }

    /**
     * Extract quick-reply options from `[quick-reply: A | B | C]` tags in the response.
     */
    private fun detectQuickReplies(responseText: String): List<String> {
        val match = ChatConsolePanel.QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Build inline snippet text for selections so the agent sees the code in the prompt itself */
    private fun buildSnippetSuffix(): String {
        val parts = mutableListOf<String>()
        for (i in 0 until contextListModel.size()) {
            val item = contextListModel.getElementAt(i)
            if (!item.isSelection || item.startLine <= 0) continue
            try {
                val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
                    ?: continue
                var doc: com.intellij.openapi.editor.Document? = null
                com.intellij.openapi.application.ReadAction.run<Throwable> {
                    doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                }
                val document = doc ?: continue
                var snippet = ""
                com.intellij.openapi.application.ReadAction.run<Throwable> {
                    val s = document.getLineStartOffset((item.startLine - 1).coerceIn(0, document.lineCount - 1))
                    val e = document.getLineEndOffset((item.endLine - 1).coerceIn(0, document.lineCount - 1))
                    snippet = document.getText(com.intellij.openapi.util.TextRange(s, e))
                }
                val fileName = item.path.substringAfterLast("/")
                val ext = fileName.substringAfterLast(".", "")
                parts.add("Selected lines ${item.startLine}-${item.endLine} of `$fileName`:\n```$ext\n$snippet\n```")
            } catch (_: Exception) { /* skip */
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun buildSingleReference(item: ContextItem): CopilotAcpClient.ResourceReference? {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
            ?: return null
        var doc: com.intellij.openapi.editor.Document? = null
        com.intellij.openapi.application.ReadAction.run<Throwable> {
            doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        }
        val document = doc ?: return null

        var text = ""
        com.intellij.openapi.application.ReadAction.run<Throwable> {
            if (item.isSelection && item.startLine > 0) {
                val startOffset = document.getLineStartOffset((item.startLine - 1).coerceIn(0, document.lineCount - 1))
                val endOffset = document.getLineEndOffset((item.endLine - 1).coerceIn(0, document.lineCount - 1))
                val snippet = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                val fileName = item.path.substringAfterLast("/")
                text = "// Selected lines ${item.startLine}-${item.endLine} of $fileName\n$snippet"
            } else {
                text = document.text
            }
        }

        val uri = buildString {
            append("file://")
            append(item.path.replace("\\", "/"))
            if (item.isSelection && item.startLine > 0) {
                append("#L${item.startLine}-L${item.endLine}")
            }
        }
        val mimeType = getMimeTypeForFileType(file.fileType.name.lowercase())
        val contextLog = com.intellij.openapi.diagnostic.Logger.getInstance("ContextSnippet")
        contextLog.info(
            "Context ref: uri=$uri, isSelection=${item.isSelection}, lines=${item.startLine}-${item.endLine}, textLength=${text.length}, textPreview=${
                text.take(
                    100
                )
            }"
        )
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
            "tool_call" -> handleStreamingToolCall(update)
            "tool_call_update" -> handleStreamingToolCallUpdate(update)
            "agent_thought_chunk" -> handleStreamingAgentThought(update, receivedContent)
        }
        handleAcpUpdate(update)
    }

    private fun extractJsonElementText(element: com.google.gson.JsonElement): String? {
        return when {
            element.isJsonObject -> com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(element)
            element.isJsonPrimitive -> element.asString
            else -> null
        }
    }

    private fun extractJsonArguments(update: com.google.gson.JsonObject): String? {
        return update["arguments"]?.let { extractJsonElementText(it) }
            ?: update["input"]?.let { extractJsonElementText(it) }
            ?: update["rawInput"]?.let { extractJsonElementText(it) }
    }

    private fun handleStreamingToolCall(update: com.google.gson.JsonObject) {
        val title = update["title"]?.asString ?: "tool"
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        turnToolCallCount++
        if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
        val arguments = extractJsonArguments(update)
        setResponseStatus("Running: $title")
        if (status != "completed" && toolCallId.isNotEmpty()) {
            // Detect sub-agent calls by checking for agent_type in arguments
            // (title is the description, not "task", e.g. "Review chat UI code")
            val agentType = extractJsonField(arguments, "agent_type")
            if (agentType != null) {
                toolCallTitles[toolCallId] = "task" // mark as sub-agent for update routing
                CopilotService.getInstance(project).getClient().setSubAgentActive(true)
                val description = title.ifBlank { extractJsonField(arguments, "description") ?: "Sub-agent task" }
                val prompt = extractJsonField(arguments, "prompt")
                consolePanel.addSubAgentEntry(toolCallId, agentType, description, prompt)
            } else {
                toolCallTitles[toolCallId] = title
                consolePanel.addToolCallEntry(toolCallId, title, arguments)
            }
        }
    }

    private fun extractJsonField(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            com.google.gson.JsonParser.parseString(json).asJsonObject[key]?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun handleStreamingToolCallUpdate(update: com.google.gson.JsonObject) {
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        val result = update["result"]?.asString
            ?: update["content"]?.let { extractContentText(it) }
        val isSubAgent = toolCallTitles[toolCallId] == "task"
        if (status == "completed") {
            setResponseStatus(MSG_THINKING)
            if (isSubAgent) {
                CopilotService.getInstance(project).getClient().setSubAgentActive(false)
                consolePanel.updateSubAgentResult(toolCallId, "completed", result)
            } else {
                consolePanel.updateToolCall(toolCallId, "completed", result)
            }
        } else if (status == "failed") {
            val error = update["error"]?.asString
                ?: result
                ?: update.toString().take(500)
            if (isSubAgent) {
                CopilotService.getInstance(project).getClient().setSubAgentActive(false)
                consolePanel.updateSubAgentResult(toolCallId, "failed", error)
            } else {
                consolePanel.updateToolCall(toolCallId, "failed", error)
            }
        }
    }

    private fun extractContentText(element: com.google.gson.JsonElement): String? {
        return try {
            when {
                element.isJsonArray -> {
                    element.asJsonArray.mapNotNull { block ->
                        extractContentBlockText(block)
                    }.joinToString("\n").ifEmpty { null }
                }

                element.isJsonObject -> element.asJsonObject["text"]?.asString
                element.isJsonPrimitive -> element.asString
                else -> null
            }
        } catch (_: Exception) {
            element.toString()
        }
    }

    private fun extractContentBlockText(block: com.google.gson.JsonElement): String? {
        if (!block.isJsonObject) return if (block.isJsonPrimitive) block.asString else block.toString()
        val obj = block.asJsonObject
        return obj["content"]?.let { inner ->
            if (inner.isJsonObject) inner.asJsonObject["text"]?.asString
            else if (inner.isJsonPrimitive) inner.asString
            else null
        } ?: obj["text"]?.asString
    }

    private fun handleStreamingAgentThought(update: com.google.gson.JsonObject, receivedContent: Boolean) {
        val content = update["content"]?.asJsonObject
        val text = content?.get("text")?.asString
        if (text != null) {
            consolePanel.appendThinkingText(text)
        }
        if (!receivedContent) {
            setResponseStatus(MSG_THINKING)
        }
    }

    private fun handlePromptError(e: Exception) {
        val msg = if (e is InterruptedException || e.cause is InterruptedException) {
            "Request cancelled"
        } else {
            e.message ?: MSG_UNKNOWN_ERROR
        }
        consolePanel.addErrorEntry("Error: $msg")
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

    private fun getModelMultiplier(modelId: String): String {
        return try {
            CopilotService.getInstance(project).getClient().getModelMultiplier(modelId)
        } catch (_: Exception) {
            "1x"
        }
    }

    private fun saveTurnStatistics(prompt: String, toolCalls: Int, modelId: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val statsDir = java.io.File(project.basePath ?: return@executeOnPooledThread, AGENT_WORK_DIR)
                statsDir.mkdirs()
                val statsFile = java.io.File(statsDir, "usage-stats.jsonl")
                val entry = com.google.gson.JsonObject().apply {
                    addProperty("timestamp", java.time.Instant.now().toString())
                    addProperty("prompt", prompt.take(200))
                    addProperty("model", modelId)
                    addProperty("multiplier", getModelMultiplier(modelId))
                    addProperty("toolCalls", toolCalls)
                }
                statsFile.appendText(entry.toString() + "\n")
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    private fun conversationFile(): java.io.File {
        val dir = java.io.File(project.basePath ?: "", AGENT_WORK_DIR)
        dir.mkdirs()
        return java.io.File(dir, "conversation.json")
    }

    private fun notifyIfUnfocused(toolCallCount: Int) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isActive) return
        val title = "Copilot response ready"
        val content =
            if (toolCallCount > 0) "Turn completed with $toolCallCount tool call${if (toolCallCount != 1) "s" else ""}"
            else "Turn completed"
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Notifications")
            .createNotification(title, content, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    private fun saveConversation() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                conversationFile().writeText(consolePanel.serializeEntries())
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    private fun restoreConversation() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = conversationFile()
                if (!file.exists() || file.length() < 10) {
                    SwingUtilities.invokeLater {
                        consolePanel.showPlaceholder("Start a conversation with Copilot...")
                    }
                    return@executeOnPooledThread
                }
                val json = file.readText()
                SwingUtilities.invokeLater {
                    consolePanel.restoreEntries(json)
                }
            } catch (_: Exception) {
                SwingUtilities.invokeLater {
                    consolePanel.showPlaceholder("Start a conversation with Copilot...")
                }
            }
        }
    }

    private fun handleAddCurrentFile() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (currentFile == null) {
            Messages.showWarningDialog(project, "No file is currently open in the editor", "No File")
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
            Messages.showInfoMessage(project, "File already in context: ${currentFile.name}", "Duplicate File")
            return
        }

        contextListModel.addElement(
            ContextItem(
                path = path, name = currentFile.name, startLine = 1, endLine = lineCount,
                fileType = currentFile.fileType, isSelection = false
            )
        )
    }

    private fun handleAddSelection() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val editor = fileEditorManager.selectedTextEditor
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (editor == null || currentFile == null) {
            Messages.showWarningDialog(project, "No editor is currently open", "No Editor")
            return
        }

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showWarningDialog(project, "No text is selected. Select some code first.", "No Selection")
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

    // Debug/Timeline/Settings tabs extracted to DebugPanel.kt
    fun getComponent(): JComponent = mainPanel
    fun openSettings() = debugPanel.openSettings()
    fun openDebug() = debugPanel.openDebug()
    fun openSessionFiles() = debugPanel.openSessionFiles()

    fun resetSession() {
        currentSessionId = null
        billing.billingCycleStartUsed = -1
        if (::processingTimerPanel.isInitialized) processingTimerPanel.resetSession()
        consolePanel.clear()
        consolePanel.showPlaceholder("New conversation started.")
        addTimelineEvent(EventType.SESSION_START, "New conversation started")
        updateSessionInfo()
        // Clear saved conversation
        try {
            val deleted = conversationFile().delete()
            if (!deleted) {
                LOG.debug("Conversation file could not be deleted (may not exist)")
            }
        } catch (_: Exception) { /* Best-effort cleanup — ignore deletion failures */
        }
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
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel
    ) {
        SwingUtilities.invokeLater {
            spinner.isVisible = true
            authPanel.isVisible = false
            retryButton.isVisible = false
            loginButton.isVisible = false
            modelsStatusText = MSG_LOADING
            selectedModelIndex = -1
        }
    }

    private fun isAuthenticationError(message: String): Boolean {
        return message.contains("auth") ||
            message.contains("Copilot CLI") ||
            message.contains("authenticated")
    }

    private fun restoreModelSelection(models: List<CopilotAcpClient.Model>) {
        val savedModel = CopilotSettings.getSelectedModel()
        LOG.info("Restoring model selection: saved='$savedModel', available=${models.map { it.id }}")
        if (savedModel != null) {
            val idx = models.indexOfFirst { it.id == savedModel }
            if (idx >= 0) {
                selectedModelIndex = idx; LOG.info("Restored model index=$idx"); return
            }
            LOG.info("Saved model '$savedModel' not found in available models")
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
    }

    private fun showModelError(
        spinner: AsyncProcessIcon,
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
            modelsStatusText = "Unavailable"
            errorLabel.text = when {
                isAuthError -> "⚠️ Not signed in — sign in to get started"
                isTimeout -> "⚠️ Connection timed out — the Copilot CLI may still be starting"
                else -> "⚠️ $errorMsg"
            }
            loginButton.isVisible = isAuthError
            retryButton.isVisible = true
            authPanel.isVisible = true
        }
    }

    private fun loadModelsAsync(
        spinner: AsyncProcessIcon,
        errorLabel: JBLabel,
        loginButton: JButton,
        retryButton: JButton,
        authPanel: JPanel,
        onSuccess: (List<CopilotAcpClient.Model>) -> Unit
    ) {
        setLoadingState(spinner, loginButton, retryButton, authPanel)
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Exception? = null
            val maxRetries = 3
            val retryDelayMs = 2000L

            for (attempt in 1..maxRetries) {
                lastError = attemptLoadModels(spinner, authPanel, onSuccess)
                if (lastError == null) return@executeOnPooledThread
                if (isAuthenticationError(lastError.message ?: "")) break
                if (attempt < maxRetries) Thread.sleep(retryDelayMs)
            }

            val errorMsg = lastError?.message ?: MSG_UNKNOWN_ERROR
            showModelError(spinner, errorLabel, loginButton, retryButton, authPanel, errorMsg)
        }
    }

    private fun attemptLoadModels(
        spinner: AsyncProcessIcon,
        authPanel: JPanel,
        onSuccess: (List<CopilotAcpClient.Model>) -> Unit
    ): Exception? {
        return try {
            val service = CopilotService.getInstance(project)
            val models = service.getClient().listModels().toList()
            SwingUtilities.invokeLater {
                spinner.isVisible = false
                modelsStatusText = null
                restoreModelSelection(models)
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
        errorLabel.foreground = ERROR_COLOR
        errorLabel.font = JBUI.Fonts.smallFont()
        authPanel.add(errorLabel)

        loginButton.text = "Sign In"
        loginButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        loginButton.toolTipText = "Opens GitHub authentication in a terminal or browser"
        loginButton.isVisible = false
        loginButton.addActionListener { startCopilotLogin() }
        authPanel.add(loginButton)

        retryButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        retryButton.toolTipText = "Try connecting to the Copilot CLI again"
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

    // TimelineEvent and EventType extracted to DebugPanel.kt

    /** Tree node that holds file content and path for the Plans tab. */
    private class FileTreeNode(
        val fileName: String,
        val filePath: String,
        val fileContent: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
