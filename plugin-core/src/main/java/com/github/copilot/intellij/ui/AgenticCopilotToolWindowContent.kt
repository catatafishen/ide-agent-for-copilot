package com.github.copilot.intellij.ui

import com.github.copilot.intellij.bridge.CopilotAcpClient
import com.github.copilot.intellij.services.CopilotService
import com.github.copilot.intellij.services.CopilotSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
                            costLabel.foreground = Color(220, 50, 50)
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
        val toolbar = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, 5, 5))
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
        val loadingSpinner = JProgressBar()
        loadingSpinner.isIndeterminate = true
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
        val authPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        authPanel.isVisible = false
        authPanel.border = JBUI.Borders.emptyLeft(5)
        authPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        
        val modelErrorLabel = JBLabel()
        modelErrorLabel.foreground = Color(200, 80, 80)
        modelErrorLabel.font = modelErrorLabel.font.deriveFont(Font.PLAIN, 11f)
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
        usageLabel.font = usageLabel.font.deriveFont(Font.PLAIN, 11f)
        usageLabel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        usagePanel.add(usageLabel)
        
        costLabel = JBLabel("")
        costLabel.font = costLabel.font.deriveFont(Font.BOLD, 11f)
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
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitPane.resizeWeight = 0.4 // 40% for input, 60% for output
        
        // Prompt input area
        val promptPanel = JBPanel<JBPanel<*>>(BorderLayout())
        promptPanel.border = JBUI.Borders.empty(5)
        
        val promptLabel = JBLabel("Prompt:")
        promptPanel.add(promptLabel, BorderLayout.NORTH)
        
        val promptTextArea = JTextArea()
        promptTextArea.lineWrap = true
        promptTextArea.wrapStyleWord = true
        promptTextArea.rows = 8
        promptTextArea.border = JBUI.Borders.empty(5)
        
        // Response output area (declare before button listener needs it)
        val responsePanel = JBPanel<JBPanel<*>>(BorderLayout())
        responsePanel.border = JBUI.Borders.empty(5)
        
        val responseLabel = JBLabel("Response:")
        responsePanel.add(responseLabel, BorderLayout.NORTH)
        
        val responseTextArea = JTextArea()
        responseTextArea.isEditable = false
        responseTextArea.lineWrap = true
        responseTextArea.wrapStyleWord = true
        responseTextArea.text = "Response will appear here after running a prompt...\n\nℹ️ First run will auto-start the sidecar process."
        responseTextArea.border = JBUI.Borders.empty(5)
        
        val responseScrollPane = JBScrollPane(responseTextArea)
        responsePanel.add(responseScrollPane, BorderLayout.CENTER)
        
        // Helper function to append to response area
        fun appendResponse(text: String) {
            SwingUtilities.invokeLater {
                responseTextArea.append(text)
                responseTextArea.caretPosition = responseTextArea.document.length
            }
        }
        
        // Add document listener for token counting
        val promptScrollPane = JBScrollPane(promptTextArea)
        promptPanel.add(promptScrollPane, BorderLayout.CENTER)
        
        // Run button
        val runButton = JButton("Run")
        runButton.addActionListener {
            val prompt = promptTextArea.text.trim()
            if (prompt.isEmpty()) {
                JOptionPane.showMessageDialog(panel, "Please enter a prompt", "Empty Prompt", JOptionPane.WARNING_MESSAGE)
                return@addActionListener
            }
            
            runButton.isEnabled = false
            responseTextArea.text = "Connecting to Copilot...\n"
            
            // Run in background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val service = ApplicationManager.getApplication().getService(CopilotService::class.java)
                    val client = service.getClient()
                    
                    // Create session if needed
                    val sessionId = client.createSession()
                    appendResponse("✅ Session created: $sessionId\n")
                    
                    // Get selected model from loaded models list
                    val selIdx = modelComboBox.selectedIndex
                    val selectedModelObj = if (selIdx >= 0 && selIdx < loadedModels.size) loadedModels[selIdx] else null
                    val modelId = selectedModelObj?.id ?: ""
                    val modelName = selectedModelObj?.name ?: "default"
                    val modelMultiplier = selectedModelObj?.usage ?: "1x"
                    appendResponse("🤖 Using model: $modelName ($modelMultiplier)\n\n")
                    appendResponse("─".repeat(50) + "\n")
                    
                    // Send prompt with streaming
                    val stopReason = client.sendPrompt(sessionId, prompt, modelId) { chunk ->
                        appendResponse(chunk)
                    }
                    
                    // Show per-prompt estimated usage (like CLI does)
                    val multiplierVal = try { modelMultiplier.replace("x", "").toDouble() } catch (_: Exception) { 1.0 }
                    val estPremium = Math.ceil(multiplierVal).toInt()
                    
                    appendResponse("\n" + "─".repeat(50) + "\n")
                    appendResponse("✅ Complete (${stopReason})\n")
                    appendResponse("Est. $estPremium premium request(s) [$modelName $modelMultiplier]\n")
                    
                    // Refresh billing data after prompt
                    loadBillingData()
                    
                } catch (e: Exception) {
                    appendResponse("\n❌ Error: ${e.message}\n")
                    e.printStackTrace()
                } finally {
                    SwingUtilities.invokeLater {
                        runButton.isEnabled = true
                    }
                }
            }
        }
        promptPanel.add(runButton, BorderLayout.SOUTH)
        
        splitPane.topComponent = promptPanel
        splitPane.bottomComponent = responsePanel
        
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
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.resizeWeight = 0.5
        
        // Left: Plans tree
        val treePanel = JBPanel<JBPanel<*>>(BorderLayout())
        treePanel.border = JBUI.Borders.empty(5)
        
        val treeLabel = JBLabel("Execution Plans:")
        treePanel.add(treeLabel, BorderLayout.NORTH)
        
        // Create sample tree structure
        val root = javax.swing.tree.DefaultMutableTreeNode("Agent Plans (Mock)")
        
        val plan1 = javax.swing.tree.DefaultMutableTreeNode("✅ Bake a Cake (Mock)")
        plan1.add(javax.swing.tree.DefaultMutableTreeNode("✅ Preheat oven to 180°C (Mock)"))
        plan1.add(javax.swing.tree.DefaultMutableTreeNode("✅ Mix flour, sugar, eggs (Mock)"))
        plan1.add(javax.swing.tree.DefaultMutableTreeNode("✅ Pour batter into pan (Mock)"))
        root.add(plan1)
        
        val plan2 = javax.swing.tree.DefaultMutableTreeNode("🔄 Launch Rocket (Mock)")
        plan2.add(javax.swing.tree.DefaultMutableTreeNode("✅ Build rocket (Mock)"))
        plan2.add(javax.swing.tree.DefaultMutableTreeNode("🔄 Fuel rocket (Mock, in progress)"))
        plan2.add(javax.swing.tree.DefaultMutableTreeNode("⏳ Countdown (Mock, pending)"))
        plan2.add(javax.swing.tree.DefaultMutableTreeNode("⏳ Liftoff! (Mock, pending)"))
        root.add(plan2)
        
        val plan3 = javax.swing.tree.DefaultMutableTreeNode("⏳ Teach Cat to Code (Mock)")
        plan3.add(javax.swing.tree.DefaultMutableTreeNode("⏳ Open laptop (Mock)"))
        plan3.add(javax.swing.tree.DefaultMutableTreeNode("⏳ Sit cat on keyboard (Mock)"))
        plan3.add(javax.swing.tree.DefaultMutableTreeNode("❌ Cat walks away (Mock)"))
        root.add(plan3)
        
        val treeModel = javax.swing.tree.DefaultTreeModel(root)
        val tree = com.intellij.ui.treeStructure.Tree(treeModel)
        tree.isRootVisible = true
        tree.showsRootHandles = true
        
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
        
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
                
                // Set icons based on status
                when {
                    text.startsWith("✅") -> icon = com.intellij.icons.AllIcons.Actions.Commit
                    text.startsWith("🔄") -> icon = com.intellij.icons.AllIcons.Actions.Execute
                    text.startsWith("⏳") -> icon = com.intellij.icons.AllIcons.Actions.Pause
                    text.startsWith("❌") -> icon = com.intellij.icons.AllIcons.General.Error
                    else -> icon = com.intellij.icons.AllIcons.Nodes.Folder
                }
                
                return label
            }
        }
        
        val treeScrollPane = JBScrollPane(tree)
        treePanel.add(treeScrollPane, BorderLayout.CENTER)
        
        splitPane.leftComponent = treePanel
        
        // Right: Plan details/diff preview
        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        detailsPanel.border = JBUI.Borders.empty(5)
        
        val detailsLabel = JBLabel("Plan Details:")
        detailsPanel.add(detailsLabel, BorderLayout.NORTH)
        
        val detailsArea = JTextArea()
        detailsArea.isEditable = false
        detailsArea.lineWrap = true
        detailsArea.wrapStyleWord = true
        detailsArea.text = """
            Select a plan item to see details.
            
            ⚠️ This tab shows MOCK data.
            Plans will be populated from the Copilot agent
            when plan mode is implemented.
        """.trimIndent()
        detailsArea.border = JBUI.Borders.empty(5)
        
        val detailsScrollPane = JBScrollPane(detailsArea)
        detailsPanel.add(detailsScrollPane, BorderLayout.CENTER)
        
        // Add selection listener
        tree.addTreeSelectionListener { event ->
            val node = event.path.lastPathComponent as? javax.swing.tree.DefaultMutableTreeNode
            val text = node?.userObject?.toString() ?: ""
            detailsArea.text = """
                Selected: $text
                
                Status: ${when {
                    text.startsWith("✅") -> "Completed"
                    text.startsWith("🔄") -> "In Progress"
                    text.startsWith("⏳") -> "Pending"
                    text.startsWith("❌") -> "Failed"
                    else -> "Plan Group"
                }}
                
                Details will be populated from agent in Phase 3.
            """.trimIndent()
        }
        
        splitPane.rightComponent = detailsPanel
        
        panel.add(splitPane, BorderLayout.CENTER)
        
        return panel
    }

    private fun createTimelineTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        
        // Timeline list
        val timelineModel = DefaultListModel<TimelineEvent>()
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
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 10, 5))
        
        val clearButton = JButton("Clear Timeline")
        clearButton.addActionListener {
            if (timelineModel.size() > 0) {
                val result = JOptionPane.showConfirmDialog(
                    panel,
                    "Clear ${timelineModel.size()} timeline events?",
                    "Clear Timeline",
                    JOptionPane.YES_NO_OPTION
                )
                if (result == JOptionPane.YES_OPTION) {
                    timelineModel.clear()
                }
            }
        }
        toolbar.add(clearButton)
        
        val exportButton = JButton("Export Timeline")
        exportButton.isEnabled = false
        exportButton.toolTipText = "Export timeline to file (coming soon)"
        toolbar.add(exportButton)
        
        panel.add(toolbar, BorderLayout.SOUTH)
        
        // Add sample events for demonstration
        timelineModel.addElement(TimelineEvent(
            EventType.SESSION_START,
            "(Mock) Session initialized",
            java.util.Date()
        ))
        timelineModel.addElement(TimelineEvent(
            EventType.MESSAGE_SENT,
            "(Mock) User asked: 'How do I bake a cake?'",
            java.util.Date(System.currentTimeMillis() - 5000)
        ))
        timelineModel.addElement(TimelineEvent(
            EventType.RESPONSE_RECEIVED,
            "(Mock) Agent replied with cake recipe",
            java.util.Date(System.currentTimeMillis() - 3000)
        ))
        timelineModel.addElement(TimelineEvent(
            EventType.TOOL_CALL,
            "(Mock) Agent called: read_file('recipe.txt')",
            java.util.Date(System.currentTimeMillis() - 1000)
        ))
        
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
        val settingsModelPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        val defaultModelCombo = ComboBox(arrayOf("Loading..."))
        defaultModelCombo.preferredSize = JBUI.size(250, 30)
        defaultModelCombo.isEnabled = false
        settingsModelPanel.add(defaultModelCombo)
        val settingsSpinner = JProgressBar()
        settingsSpinner.isIndeterminate = true
        settingsSpinner.preferredSize = JBUI.size(16, 16)
        settingsModelPanel.add(settingsSpinner)
        panel.add(settingsModelPanel, gbc)
        
        // Auth row for settings tab
        gbc.gridx = 0
        gbc.gridy++
        gbc.gridwidth = 2
        val settingsAuthPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        settingsAuthPanel.isVisible = false
        val settingsModelError = JBLabel()
        settingsModelError.foreground = Color(200, 80, 80)
        settingsModelError.font = settingsModelError.font.deriveFont(Font.PLAIN, 11f)
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
