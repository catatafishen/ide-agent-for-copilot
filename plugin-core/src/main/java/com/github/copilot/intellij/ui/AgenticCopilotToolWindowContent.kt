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
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.geom.Path2D
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    private lateinit var sessionInfoLabel: JBLabel

    // Usage display components (updated after each prompt)
    private val usageLabel: JBLabel = JBLabel("")
    private val costLabel: JBLabel = JBLabel("")
    private lateinit var consolePanel: ChatConsolePanel

    // Per-turn tracking
    private var turnToolCallCount = 0
    private var turnModelId = ""

    // Animation state for usage indicator
    private var previousUsedCount = -1
    private var usageAnimationTimer: javax.swing.Timer? = null

    // Usage display toggle and graph
    private enum class UsageDisplayMode { MONTHLY, SESSION }

    private var usageDisplayMode = UsageDisplayMode.MONTHLY
    private var billingCycleStartUsed = -1
    private lateinit var usageGraphPanel: UsageGraphPanel
    private var lastBillingUsed = 0
    private var lastBillingEntitlement = 0
    private var lastBillingUnlimited = false
    private var lastBillingRemaining = 0
    private var lastBillingOveragePermitted = false
    private var lastBillingResetDate = ""
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
        val shouldAnimate = previousUsedCount >= 0 && used > previousUsedCount
        previousUsedCount = used

        // Track session start baseline
        if (billingCycleStartUsed < 0) billingCycleStartUsed = used

        // Store latest billing data for toggle
        lastBillingUsed = used
        lastBillingEntitlement = entitlement
        lastBillingUnlimited = unlimited
        lastBillingRemaining = remaining
        lastBillingOveragePermitted = overagePermitted
        lastBillingResetDate = resetDate

        SwingUtilities.invokeLater {
            refreshUsageDisplay()
            updateUsageGraph(used, entitlement, unlimited, resetDate)
            if (shouldAnimate) animateUsageChange()
        }
    }

    /** Refreshes usage label and cost label based on current display mode. */
    private fun refreshUsageDisplay() {
        when (usageDisplayMode) {
            UsageDisplayMode.MONTHLY -> {
                if (lastBillingUnlimited) {
                    usageLabel.text = "Unlimited"
                    usageLabel.toolTipText = "Click to show session usage"
                    costLabel.text = ""
                } else {
                    usageLabel.text = "$lastBillingUsed / $lastBillingEntitlement"
                    usageLabel.toolTipText = "Premium requests this cycle \u2022 Click to show session usage"
                    updateCostLabel(lastBillingRemaining, lastBillingOveragePermitted)
                }
            }

            UsageDisplayMode.SESSION -> {
                val sessionUsed = lastBillingUsed - billingCycleStartUsed.coerceAtLeast(0)
                usageLabel.text = "$sessionUsed session"
                usageLabel.toolTipText = "Premium requests this session \u2022 Click to show monthly usage"
                costLabel.text = ""
            }
        }
    }

    /** Updates the mini usage graph with current billing cycle data. */
    private fun updateUsageGraph(
        used: Int,
        entitlement: Int,
        unlimited: Boolean,
        resetDate: String
    ) {
        if (!::usageGraphPanel.isInitialized) return
        if (unlimited || entitlement <= 0) {
            usageGraphPanel.graphData = null
            usageGraphPanel.repaint()
            return
        }

        try {
            val resetLocalDate = LocalDate.parse(resetDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val today = LocalDate.now()
            val cycleStart = resetLocalDate.minusMonths(1)
            val totalDays = ChronoUnit.DAYS.between(cycleStart, resetLocalDate).toInt().coerceAtLeast(1)
            val currentDay = ChronoUnit.DAYS.between(cycleStart, today).toInt().coerceIn(0, totalDays)

            usageGraphPanel.graphData = UsageGraphData(currentDay, totalDays, used, entitlement)
            usageGraphPanel.toolTipText =
                buildGraphTooltip(used, entitlement, currentDay, totalDays, resetLocalDate)
            usageGraphPanel.repaint()
        } catch (_: Exception) {
            usageGraphPanel.graphData = null
            usageGraphPanel.repaint()
        }
    }

    private fun buildGraphTooltip(
        used: Int,
        entitlement: Int,
        currentDay: Int,
        totalDays: Int,
        resetDate: LocalDate,
    ): String {
        val rate = if (currentDay > 0) used.toFloat() / currentDay else 0f
        val projected = (rate * totalDays).toInt()
        val overage = (used - entitlement).coerceAtLeast(0)
        val projectedOverage = (projected - entitlement).coerceAtLeast(0)
        val overageCostPerReq = 0.04
        val resetFormatted = resetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

        val sb = StringBuilder("<html>")
        sb.append("Day $currentDay / $totalDays<br>")
        sb.append("Usage: $used / $entitlement<br>")
        if (overage > 0) {
            val cost = overage * overageCostPerReq
            sb.append("<font color='#E04040'>Overage: $overage reqs (\$${String.format("%.2f", cost)})</font><br>")
        }
        sb.append("Projected: ~$projected by cycle end<br>")
        if (projectedOverage > 0) {
            val projCost = projectedOverage * overageCostPerReq
            sb.append(
                "<font color='#E04040'>Projected overage: ~$projectedOverage (\$${
                    String.format(
                        "%.2f",
                        projCost
                    )
                })</font><br>"
            )
        }
        sb.append("Resets: $resetFormatted")
        sb.append("</html>")
        return sb.toString()
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

    /** Briefly pulses [usageLabel] foreground from a green accent back to normal. */
    private fun animateUsageChange() {
        usageAnimationTimer?.stop()
        val normalColor = usageLabel.foreground
        val highlightColor = JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
        val totalSteps = 20
        var step = 0

        usageLabel.foreground = highlightColor
        usageAnimationTimer = javax.swing.Timer(50) {
            step++
            if (step >= totalSteps) {
                usageLabel.foreground = normalColor
                usageAnimationTimer?.stop()
            } else {
                val ratio = step.toFloat() / totalSteps
                usageLabel.foreground = interpolateColor(highlightColor, normalColor, ratio)
            }
        }
        usageAnimationTimer!!.start()
    }

    private fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        val r = (from.red + (to.red - from.red) * ratio).toInt()
        val g = (from.green + (to.green - from.green) * ratio).toInt()
        val b = (from.blue + (to.blue - from.blue) * ratio).toInt()
        return Color(r, g, b)
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

        loadBillingData()

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
        footer.border = JBUI.Borders.empty(0, 0, 2, 0)

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
            setupPromptContextMenu(promptTextArea, editor)
            // Use EditorEx built-in placeholder (visual-only, doesn't set actual text)
            editor.setPlaceholder(PROMPT_PLACEHOLDER)
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps = true
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
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
        scrollPane.border = null
        inputWrapper.add(scrollPane, BorderLayout.CENTER)
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
                chip.background = JBColor(Color(0xE8, 0xEE, 0xF7), Color(0x35, 0x3B, 0x48))
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
        // [Send] | [Attach File, Attach Selection] | [Model, Mode] | [Follow, Format, Build, Test, Commit] | [Instructions, TODO] | [Export, Help]
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
        leftGroup.add(OpenInstructionsAction())
        leftGroup.add(OpenTodoAction())
        leftGroup.addSeparator()
        leftGroup.add(CopyConversationAction())
        leftGroup.add(HelpAction())

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.setReservePlaceAutoPopupIcon(false)

        // Right toolbar: processing indicator + usage graph (always right-aligned)
        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        rightGroup.add(UsageGraphAction())

        val usageToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotUsage", rightGroup, true
        )
        usageToolbar.targetComponent = row
        usageToolbar.setReservePlaceAutoPopupIcon(false)

        row.add(controlsToolbar.component, BorderLayout.CENTER)
        row.add(usageToolbar.component, BorderLayout.EAST)

        return row
    }

    /** Toolbar action showing the usage sparkline graph as a clickable custom component */
    private inner class UsageGraphAction : AnAction("Usage Graph"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            showUsagePopup(usageGraphPanel)
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            usageGraphPanel = UsageGraphPanel()
            usageGraphPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            usageGraphPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    showUsagePopup(usageGraphPanel)
                }
            })
            return usageGraphPanel
        }
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
    private inner class ProcessingTimerPanel : JBPanel<ProcessingTimerPanel>(FlowLayout(FlowLayout.RIGHT, 4, 0)) {
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
            isOpaque = false
            border = JBUI.Borders.emptyRight(6)
            val smallGray = JBUI.Fonts.smallFont()
            spinner.isVisible = false
            doneIcon.isVisible = false
            doneIcon.font = smallGray
            timerLabel.foreground = JBColor.GRAY; timerLabel.font = smallGray; timerLabel.isVisible = false
            toolsLabel.foreground = JBColor.GRAY; toolsLabel.font = smallGray; toolsLabel.isVisible = false
            requestsLabel.foreground = JBColor.GRAY; requestsLabel.font = smallGray; requestsLabel.isVisible = false
            add(spinner)
            add(doneIcon)
            add(timerLabel)
            add(toolsLabel)
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
                    modeTurn -> {
                        toolTipText = "Click to show session stats"
                        updateLabel()
                        toolsLabel.text = if (toolCallCount > 0) "\u2022 $toolCallCount tools" else ""
                        toolsLabel.isVisible = toolCallCount > 0
                        requestsLabel.text = if (requestsUsed > 0) "\u2022 $requestsUsed req" else ""
                        requestsLabel.isVisible = requestsUsed > 0
                        if (!isRunning) doneIcon.text = "\u2705"
                    }

                    modeSession -> {
                        toolTipText = "Click to show turn stats"
                        val totalMs =
                            sessionTotalTimeMs + if (isRunning) (System.currentTimeMillis() - startedAt) else 0
                        val totalSec = totalMs / 1000
                        timerLabel.text = if (totalSec < 60) "${totalSec}s" else "${totalSec / 60}m ${totalSec % 60}s"
                        val totalTools = sessionTotalToolCalls + if (isRunning) toolCallCount else 0
                        toolsLabel.text = if (totalTools > 0) "\u2022 $totalTools tools" else ""
                        toolsLabel.isVisible = totalTools > 0
                        // Prefer billing API diff if available
                        val billingReqs = if (billingCycleStartUsed >= 0 && lastBillingUsed > billingCycleStartUsed)
                            lastBillingUsed - billingCycleStartUsed else -1
                        val totalReqs = sessionTotalRequests + if (isRunning) requestsUsed else 0
                        if (billingReqs > 0) {
                            requestsLabel.text = "\u2022 $billingReqs req"
                        } else if (totalReqs > 0) {
                            requestsLabel.text = "\u2022 ~$totalReqs req"
                        } else {
                            requestsLabel.text = ""
                        }
                        requestsLabel.isVisible = requestsLabel.text.isNotEmpty()
                        doneIcon.text = "\u2211"
                    }
                }
                revalidate(); repaint()
            }
        }

        private fun updateLabel() {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            timerLabel.text = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
        }
    }

    private fun showUsagePopup(owner: JComponent) {
        val data = (if (::usageGraphPanel.isInitialized) usageGraphPanel.graphData else null) ?: return

        val popupGraph = UsageGraphPanel()
        popupGraph.graphData = data
        val pw = JBUI.scale(320)
        val ph = JBUI.scale(180)
        popupGraph.preferredSize = Dimension(pw, ph)
        popupGraph.minimumSize = popupGraph.preferredSize
        popupGraph.maximumSize = popupGraph.preferredSize

        val rate = if (data.currentDay > 0) data.usedSoFar.toFloat() / data.currentDay else 0f
        val projected = (rate * data.totalDays).toInt()
        val overQuota = data.usedSoFar > data.entitlement

        val infoHtml = buildString {
            append("<html>")
            append("Used: <b>${data.usedSoFar}</b> / ${data.entitlement}")
            append(" &nbsp;\u00B7&nbsp; Day ${data.currentDay} of ${data.totalDays}")
            append(" &nbsp;\u00B7&nbsp; Projected: ~$projected")
            if (overQuota) {
                val overage = data.usedSoFar - data.entitlement
                append("<br><font color='#E04040'>Over quota by $overage requests</font>")
            }
            if (lastBillingResetDate.isNotEmpty()) {
                try {
                    val resetDate = LocalDate.parse(lastBillingResetDate, DateTimeFormatter.ISO_LOCAL_DATE)
                    append("<br>Resets: ${resetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}")
                } catch (_: Exception) { /* Date parse failed — skip reset date display */
                }
            }
            append("</html>")
        }

        val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel("Premium Request Usage").apply {
                font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 1)
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            add(popupGraph, BorderLayout.CENTER)
            add(JBLabel(infoHtml).apply {
                border = JBUI.Borders.emptyTop(8)
            }, BorderLayout.SOUTH)
        }

        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(false)
            .createPopup()
            .showUnderneathOf(owner)
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
            handleAddCurrentFile(mainPanel)
        }
    }

    // Attach current selection to the next prompt
    private inner class AttachSelectionAction : AnAction(
        "Attach Selection", "Attach selected text to prompt", com.intellij.icons.AllIcons.Actions.AddMulticaret
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            handleAddSelection(mainPanel)
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

    private inner class HelpAction : AnAction(
        "Help", "Show help for all toolbar features and plugin behavior",
        com.intellij.icons.AllIcons.Actions.Help
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            data class HelpRow(val icon: javax.swing.Icon, val name: String, val description: String)

            val toolbarItems = listOf(
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Execute,
                    "Send",
                    "Send your prompt to the agent. Shortcut: Enter. Use Shift+Enter for a new line."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Suspend,
                    "Stop",
                    "While the agent is working, this replaces Send. Stops the current agent turn."
                ),
                null,
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.AddFile,
                    "Attach File",
                    "Attach the currently open editor file to your prompt as context."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.AddMulticaret,
                    "Attach Selection",
                    "Attach the current text selection from the editor to your prompt."
                ),
                null,
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Lightning,
                    "Model",
                    "Dropdown: choose the AI model. Premium models show a cost multiplier (e.g. \"50×\")."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.General.Settings,
                    "Mode",
                    "Dropdown: Agent = autonomous tool use. Plan = conversation only, no tool calls."
                ),
                null,
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Preview,
                    "Follow Agent",
                    "Toggle: auto-open files in the editor as the agent reads or writes them."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.ReformatCode,
                    "Format",
                    "Toggle: instruct the agent to auto-format code after editing files."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Compile,
                    "Build",
                    "Toggle: instruct the agent to build the project before completing its turn."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Nodes.Test,
                    "Test",
                    "Toggle: instruct the agent to run tests before completing its turn."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Commit,
                    "Commit",
                    "Toggle: instruct the agent to auto-commit changes before completing its turn."
                ),
                null,
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.IntentionBulb,
                    "Instructions",
                    "Open copilot-instructions.md — persistent instructions the agent follows every turn."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.General.TodoDefault,
                    "TODO",
                    "Open TODO.md — a task list the agent can read and update."
                ),
                null,
                HelpRow(
                    com.intellij.icons.AllIcons.ToolbarDecorator.Export,
                    "Export Chat",
                    "Copy the full conversation to clipboard (as text or HTML)."
                ),
                HelpRow(com.intellij.icons.AllIcons.Actions.Help, "Help", "This dialog."),
            )

            val titleBarItems = listOf(
                HelpRow(
                    com.intellij.icons.AllIcons.Actions.Restart,
                    "New Chat",
                    "Start a fresh conversation (top-right of the tool window)."
                ),
                HelpRow(
                    com.intellij.icons.AllIcons.General.Settings,
                    "Settings",
                    "Configure inactivity timeout and max tool calls per turn."
                ),
            )

            val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(12)

                val mainPanel = JBPanel<JBPanel<*>>().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)

                    // Title
                    add(JBLabel("Agentic Copilot — Toolbar Guide").apply {
                        font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 4)
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(4)
                    })
                    add(JBLabel("Each button in the toolbar, from left to right:").apply {
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(8)
                    })

                    // Toolbar items
                    for (item in toolbarItems) {
                        if (item == null) {
                            add(javax.swing.JSeparator(javax.swing.SwingConstants.HORIZONTAL).apply {
                                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                                maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(8))
                            })
                        } else {
                            add(createHelpRow(item.icon, item.name, item.description))
                        }
                    }

                    // Right side section
                    add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                    add(JBLabel("Right Side").apply {
                        font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 2)
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(4)
                    })
                    add(JBLabel("A processing timer appears while the agent is working. Next to it, a usage graph shows premium requests consumed — click it for details.").apply {
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(8)
                    })

                    // Title bar section
                    add(JBLabel("Title Bar").apply {
                        font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 2)
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(4)
                    })
                    for (item in titleBarItems) {
                        add(createHelpRow(item.icon, item.name, item.description))
                    }

                    // Chat panel section
                    add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                    add(JBLabel("Chat Panel").apply {
                        font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + 2)
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(4)
                    })
                    add(JBLabel("Agent responses render as Markdown with syntax-highlighted code blocks. Tool calls appear as collapsible chips — click to expand and see arguments/results.").apply {
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    })
                }

                val scrollPane = com.intellij.ui.components.JBScrollPane(mainPanel).apply {
                    preferredSize = java.awt.Dimension(JBUI.scale(580), JBUI.scale(520))
                    border = null
                }
                add(scrollPane, BorderLayout.CENTER)
            }

            com.intellij.openapi.ui.DialogBuilder(project).apply {
                setTitle("Agentic Copilot \u2014 Help")
                setCenterPanel(content)
                removeAllActions()
                addOkAction()
                show()
            }
        }

        private fun createHelpRow(icon: javax.swing.Icon, name: String, description: String): JBPanel<JBPanel<*>> {
            return JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(32))
                border = JBUI.Borders.empty(2, 0)

                add(JBLabel(icon).apply {
                    preferredSize = java.awt.Dimension(JBUI.scale(20), JBUI.scale(20))
                    horizontalAlignment = javax.swing.SwingConstants.CENTER
                }, BorderLayout.WEST)

                add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBLabel(name).apply {
                        font = font.deriveFont(Font.BOLD)
                        preferredSize = java.awt.Dimension(JBUI.scale(100), preferredSize.height)
                        minimumSize = preferredSize
                    }, BorderLayout.WEST)
                    add(JBLabel(description), BorderLayout.CENTER)
                }, BorderLayout.CENTER)
            }
        }
    }

    /** Open a project-root file in the editor if it exists */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private inner class OpenInstructionsAction : AnAction(
        "Instructions", "Open copilot-instructions.md",
        com.intellij.icons.AllIcons.Actions.IntentionBulb
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            val base = project.basePath
            e.presentation.isEnabled = base != null && java.io.File(base, "copilot-instructions.md").exists()
        }

        override fun actionPerformed(e: AnActionEvent) = openProjectFile("copilot-instructions.md")
    }

    private inner class OpenTodoAction : AnAction(
        "TODO", "Open TODO.md",
        com.intellij.icons.AllIcons.General.TodoDefault
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            val base = project.basePath
            e.presentation.isEnabled = base != null && java.io.File(base, "TODO.md").exists()
        }

        override fun actionPerformed(e: AnActionEvent) = openProjectFile("TODO.md")
    }

    // ComboBoxAction for model selection ? matches Run panel dropdown style
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            loadedModels.forEachIndexed { index, model ->
                val cost = model.usage ?: "1x"
                // TODO: Once Copilot CLI supports session/set_config_option for model
                //  switching (https://github.com/github/copilot-cli/issues/1485),
                //  replace the restart workaround with a mid-session config change.
                group.add(object : AnAction("${model.name}  ($cost)") {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (index == selectedModelIndex) return

                        val message = javax.swing.JEditorPane(
                            "text/html",
                            "<html><body style='width:320px'>" +
                                "Switching to <b>${model.name}</b> will reset the current session.<br><br>" +
                                "This is required because the Copilot CLI does not yet support " +
                                "mid-session model changes via the ACP protocol.<br><br>" +
                                "<a href='https://github.com/github/copilot-cli/issues/1485'>" +
                                "github/copilot-cli#1485</a></body></html>"
                        ).apply {
                            isEditable = false
                            isOpaque = false
                            addHyperlinkListener { evt ->
                                if (evt.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                                    com.intellij.ide.BrowserUtil.browse(evt.url)
                                }
                            }
                        }

                        val result = javax.swing.JOptionPane.showConfirmDialog(
                            null, message, "Change Model",
                            javax.swing.JOptionPane.OK_CANCEL_OPTION,
                            javax.swing.JOptionPane.INFORMATION_MESSAGE
                        )
                        if (result != javax.swing.JOptionPane.OK_OPTION) return

                        selectedModelIndex = index
                        CopilotSettings.setSelectedModel(model.id)
                        LOG.info("Model selected: ${model.id} (index=$index), restarting CLI")
                        currentSessionId = null
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                CopilotService.getInstance(project).restartWithModel(model.id)
                            } catch (ex: Exception) {
                                LOG.warn("Failed to restart CLI with model ${model.id}", ex)
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

    private fun setupPromptContextMenu(textArea: EditorTextField, editor: EditorEx) {
        val popup = javax.swing.JPopupMenu()

        // Edit actions
        val cutAction = javax.swing.JMenuItem("Cut").apply {
            accelerator =
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
            addActionListener {
                val editor = textArea.editor ?: return@addActionListener
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    val start = selectionModel.selectionStart
                    val end = selectionModel.selectionEnd
                    val selectedText = editor.document.getText(com.intellij.openapi.util.TextRange(start, end))
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(selectedText),
                        null
                    )
                    editor.document.deleteString(start, end)
                }
            }
        }
        val copyAction = javax.swing.JMenuItem("Copy").apply {
            accelerator =
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
            addActionListener {
                val editor = textArea.editor ?: return@addActionListener
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    val start = selectionModel.selectionStart
                    val end = selectionModel.selectionEnd
                    val selectedText = editor.document.getText(com.intellij.openapi.util.TextRange(start, end))
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(selectedText),
                        null
                    )
                }
            }
        }
        val pasteAction = javax.swing.JMenuItem("Paste").apply {
            accelerator =
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
            addActionListener {
                val editor = textArea.editor ?: return@addActionListener
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val pastedText = clipboard.getContents(null)
                    .getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (pastedText != null) {
                    val offset = editor.caretModel.offset
                    editor.document.insertString(offset, pastedText)
                }
            }
        }
        val selectAllAction = javax.swing.JMenuItem("Select All").apply {
            accelerator =
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
            addActionListener { textArea.selectAll() }
        }

        popup.add(cutAction)
        popup.add(copyAction)
        popup.add(pasteAction)
        popup.add(selectAllAction)
        popup.addSeparator()

        // Attach actions
        popup.add(javax.swing.JMenuItem("Attach Current File").apply {
            icon = com.intellij.icons.AllIcons.Actions.AddFile
            addActionListener { handleAddCurrentFile(mainPanel) }
        })
        popup.add(javax.swing.JMenuItem("Attach Editor Selection").apply {
            icon = com.intellij.icons.AllIcons.Actions.AddMulticaret
            addActionListener { handleAddSelection(mainPanel) }
        })

        // Context management
        popup.add(javax.swing.JMenuItem("Clear Attachments").apply {
            icon = com.intellij.icons.AllIcons.Actions.GC
            addActionListener { contextListModel.clear() }
            isEnabled = contextListModel.size() > 0
        })
        popup.addSeparator()

        // Conversation actions
        popup.add(javax.swing.JMenuItem("New Conversation").apply {
            icon = com.intellij.icons.AllIcons.General.Add
            addActionListener {
                currentSessionId = null
                consolePanel.addSessionSeparator(
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                )
                updateSessionInfo()
            }
        })

        // Install on the editor's content component (not the wrapper)
        // so it overrides the default editor popup
        editor.contentComponent.componentPopupMenu = popup

        // Update enabled states dynamically before showing
        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                val hasSelection = editor.selectionModel.hasSelection()
                val hasText = textArea.text.isNotBlank()
                cutAction.isEnabled = hasSelection
                copyAction.isEnabled = hasSelection
                selectAllAction.isEnabled = hasText
                // Re-check context items count
                popup.components.filterIsInstance<javax.swing.JMenuItem>()
                    .find { it.text == "Clear Attachments" }?.isEnabled = contextListModel.size() > 0
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) { /* No action needed */
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) { /* No action needed */
            }
        })
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

            val selectedModelObj =
                if (selectedModelIndex >= 0 && selectedModelIndex < loadedModels.size) loadedModels[selectedModelIndex] else null
            val modelId = selectedModelObj?.id ?: ""

            // Reset per-turn tracking
            turnToolCallCount = 0
            turnModelId = modelId

            // Show model + multiplier on the prompt bubble immediately
            SwingUtilities.invokeLater {
                consolePanel.setPromptStats(modelId, getModelMultiplier(modelId))
            }

            val references = buildContextReferences()
            // Inline selection snippets into the prompt so the agent can't ignore them
            val snippetSuffix = buildSnippetSuffix()
            var effectivePrompt = if (snippetSuffix.isNotEmpty()) "$prompt\n\n$snippetSuffix" else prompt

            // Inject compressed conversation history on first prompt of a new session
            if (!conversationSummaryInjected) {
                conversationSummaryInjected = true
                val summary = consolePanel.getCompressedSummary()
                if (summary.isNotEmpty()) {
                    effectivePrompt = "$summary\n\n$effectivePrompt"
                }
            }
            if (references.isNotEmpty()) {
                val contextFiles = (0 until contextListModel.size()).map { i ->
                    val item = contextListModel.getElementAt(i)
                    Pair(item.name, item.path)
                }
                consolePanel.addContextFilesEntry(contextFiles)
            }

            // Auto-clear attachments after building references
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
                    // Called each time a session/prompt RPC request is sent (including retries)
                    val mult = getModelMultiplier(modelId).removeSuffix("x").toIntOrNull() ?: 1
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementRequests(mult)
                }
            )

            // Auto-format and optimize imports on all files modified during this turn
            com.github.copilot.intellij.psi.PsiBridgeService.getInstance(project).flushPendingAutoFormat()

            consolePanel.finishResponse(turnToolCallCount, turnModelId, getModelMultiplier(turnModelId))
            setResponseStatus("Done", loading = false)
            addTimelineEvent(EventType.RESPONSE_RECEIVED, "Response received")
            saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
            saveConversation()
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

    /** Build inline snippet text for selections so the agent sees the code in the prompt itself */
    private fun buildSnippetSuffix(): String {
        val parts = mutableListOf<String>()
        for (i in 0 until contextListModel.size()) {
            val item = contextListModel.getElementAt(i)
            if (!item.isSelection || item.startLine <= 0) continue
            try {
                val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
                    ?: continue
                val doc =
                    com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                    } ?: continue
                val snippet = com.intellij.openapi.application.ReadAction.compute<String, Throwable> {
                    val s = doc.getLineStartOffset((item.startLine - 1).coerceIn(0, doc.lineCount - 1))
                    val e = doc.getLineEndOffset((item.endLine - 1).coerceIn(0, doc.lineCount - 1))
                    doc.getText(com.intellij.openapi.util.TextRange(s, e))
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
        val doc =
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            } ?: return null

        val text = com.intellij.openapi.application.ReadAction.compute<String, Throwable> {
            if (item.isSelection && item.startLine > 0) {
                val startOffset = doc.getLineStartOffset((item.startLine - 1).coerceIn(0, doc.lineCount - 1))
                val endOffset = doc.getLineEndOffset((item.endLine - 1).coerceIn(0, doc.lineCount - 1))
                val snippet = doc.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                // Prepend line range header so the agent knows this is a snippet
                val fileName = item.path.substringAfterLast("/")
                "// Selected lines ${item.startLine}-${item.endLine} of $fileName\n$snippet"
            } else {
                doc.text
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
            toolCallTitles[toolCallId] = title
            consolePanel.addToolCallEntry(toolCallId, title, arguments)
        }
    }

    private fun handleStreamingToolCallUpdate(update: com.google.gson.JsonObject) {
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        val result = update["result"]?.asString
            ?: update["content"]?.let { extractContentText(it) }
        if (status == "completed") {
            setResponseStatus(MSG_THINKING)
            consolePanel.updateToolCall(toolCallId, "completed", result)
        } else if (status == "failed") {
            val error = update["error"]?.asString
                ?: result
                ?: update.toString().take(500)
            consolePanel.updateToolCall(toolCallId, "failed", error)
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

    private fun handleAddCurrentFile(panel: JBPanel<JBPanel<*>>) {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (currentFile == null) {
            javax.swing.JOptionPane.showMessageDialog(
                panel,
                "No file is currently open in the editor",
                "No File",
                javax.swing.JOptionPane.WARNING_MESSAGE
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
            javax.swing.JOptionPane.showMessageDialog(
                panel,
                "File already in context: ${currentFile.name}",
                "Duplicate File",
                javax.swing.JOptionPane.INFORMATION_MESSAGE
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
            javax.swing.JOptionPane.showMessageDialog(
                panel,
                "No editor is currently open",
                "No Editor",
                javax.swing.JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            javax.swing.JOptionPane.showMessageDialog(
                panel,
                "No text is selected. Select some code first.",
                "No Selection",
                javax.swing.JOptionPane.WARNING_MESSAGE
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

        // Build info header
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

    private fun createLogViewerTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        val logArea = JBTextArea()
        logArea.isEditable = false
        logArea.font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 11)

        // Load plugin-related logs from idea.log
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

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
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

    /**
     * Creates the settings panel and returns it along with a save callback.
     */
    private fun createSettingsTab(): Pair<JComponent, () -> Unit> {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // --- Agent behavior section ---
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

        // Add filler to push everything to top
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

    fun getComponent(): JComponent = mainPanel

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
                val tabs = com.intellij.ui.components.JBTabbedPane()
                tabs.addTab("Events", createDebugTab())
                tabs.addTab("Timeline", createTimelineTab())
                tabs.addTab("Plugin Logs", createLogViewerTab())
                wrapper.add(tabs, BorderLayout.CENTER)
                return wrapper
            }
        }
        dialog.show()
    }

    fun resetSession() {
        currentSessionId = null
        billingCycleStartUsed = -1
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
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")

// --- Usage graph ---

    private data class UsageGraphData(
        val currentDay: Int,
        val totalDays: Int,
        val usedSoFar: Int,
        val entitlement: Int
    )

    /**
     * Tiny sparkline panel showing cumulative usage over the billing cycle,
     * a linear projection to end-of-month, and a horizontal entitlement bar.
     * Shows red fill for usage above the entitlement threshold.
     */
    private class UsageGraphPanel : JPanel() {
        var graphData: UsageGraphData? = null

        init {
            isOpaque = false
            val h = JBUI.scale(28)
            preferredSize = Dimension(JBUI.scale(120), h)
            minimumSize = preferredSize
            maximumSize = Dimension(JBUI.scale(120), h)
            border = JBUI.Borders.empty(1, JBUI.scale(2))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val data = graphData ?: return
            if (data.entitlement <= 0) return

            val g2 = (g as Graphics2D).also {
                it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            }
            val pad = 1
            val w = width - 2 * pad
            val h = height - 2 * pad
            if (w <= 0 || h <= 0) return

            val arc = JBUI.scale(6)

            // Border — light gray, matching ComboBoxAction style
            val borderShape = java.awt.geom.RoundRectangle2D.Float(
                0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc.toFloat(), arc.toFloat()
            )
            g2.color = UIManager.getColor("Component.borderColor") ?: JBColor(0xC4C4C4, 0x5E6060)
            g2.stroke = BasicStroke(1f)
            g2.draw(borderShape)

            val clipShape = java.awt.geom.RoundRectangle2D.Float(
                pad.toFloat(), pad.toFloat(), w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat()
            )

            // Clip all content to the rounded rect
            val oldClip = g2.clip
            g2.clip(clipShape)

            val rate = if (data.currentDay > 0) data.usedSoFar.toFloat() / data.currentDay else 0f
            val projected = (rate * data.totalDays).toInt()
            val maxY = maxOf(data.entitlement, projected, data.usedSoFar) * 1.15f
            val overQuota = data.usedSoFar > data.entitlement

            fun dx(day: Float) = pad + (day / data.totalDays * w)
            fun dy(v: Float) = pad + h - (v / maxY * h)

            // Entitlement line (dashed)
            val entY = dy(data.entitlement.toFloat())
            g2.color = if (overQuota)
                JBColor(Color(0xE0, 0x40, 0x40, 0x70), Color(0xE0, 0x60, 0x60, 0x70))
            else
                JBColor(Color(0x80, 0x80, 0x80, 0x40), Color(0xA0, 0xA0, 0xA0, 0x40))
            g2.stroke = BasicStroke(
                1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                floatArrayOf(3f, 3f), 0f
            )
            g2.drawLine(pad, entY.toInt(), pad + w, entY.toInt())

            val baseY = dy(0f)
            val curX = dx(data.currentDay.toFloat())
            val curY = dy(data.usedSoFar.toFloat())

            if (overQuota) {
                val quotaY = dy(data.entitlement.toFloat())
                // Intersection of usage line with entitlement line
                val t = (quotaY - baseY) / (curY - baseY)
                val intersectX = pad + t * (curX - pad)

                // Below-quota area (green quadrilateral)
                val belowPath = Path2D.Float().apply {
                    moveTo(pad.toFloat(), baseY)
                    lineTo(intersectX, quotaY)
                    lineTo(curX, quotaY)
                    lineTo(curX, baseY)
                    closePath()
                }
                g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x30), Color(0x6A, 0xAB, 0x73, 0x30))
                g2.fill(belowPath)

                // Over-quota area (red triangle)
                val overPath = Path2D.Float().apply {
                    moveTo(intersectX, quotaY)
                    lineTo(curX, curY)
                    lineTo(curX, quotaY)
                    closePath()
                }
                g2.color = JBColor(Color(0xE0, 0x40, 0x40, 0x40), Color(0xE0, 0x60, 0x60, 0x40))
                g2.fill(overPath)

                // Usage line (red)
                g2.color = JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
            } else {
                // Normal: green filled area
                val areaPath = Path2D.Float().apply {
                    moveTo(pad.toFloat(), baseY)
                    lineTo(curX, curY)
                    lineTo(curX, baseY)
                    closePath()
                }
                g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x40), Color(0x6A, 0xAB, 0x73, 0x40))
                g2.fill(areaPath)

                // Usage line (green)
                g2.color = JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
            }

            // Projection line (dashed gray)
            if (data.currentDay < data.totalDays) {
                val projX = dx(data.totalDays.toFloat())
                val projY = dy(projected.toFloat())
                g2.color = JBColor(Color(0x80, 0x80, 0x80, 0x80), Color(0xA0, 0xA0, 0xA0, 0x80))
                g2.stroke = BasicStroke(
                    1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    floatArrayOf(3f, 3f), 0f
                )
                g2.drawLine(curX.toInt(), curY.toInt(), projX.toInt(), projY.toInt())
            }

            // Current day dot
            val dotColor = if (overQuota)
                JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
            else
                JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
            g2.color = dotColor
            g2.fillOval(
                curX.toInt() - JBUI.scale(2), curY.toInt() - JBUI.scale(2),
                JBUI.scale(4), JBUI.scale(4)
            )

            // Restore clip
            g2.clip = oldClip
        }
    }
}
