package com.github.copilot.intellij.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.io.File
import java.util.*
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Chat-style conversation panel with speech bubbles, collapsible thinking,
 * tool call pills, and clickable file links — rendered in a single JBCefBrowser.
 */
class ChatConsolePanel(private val project: Project) : JBPanel<ChatConsolePanel>(BorderLayout()), Disposable {

    companion object {
        private fun getThemeColor(key: String, lightFallback: Color, darkFallback: Color): Color {
            return UIManager.getColor(key) ?: JBColor(lightFallback, darkFallback)
        }

        internal const val LINK_COLOR_KEY = "Component.linkColor"

        internal val USER_COLOR: Color
            get() = getThemeColor(LINK_COLOR_KEY, Color(0x29, 0x79, 0xFF), Color(0x5C, 0x9D, 0xFF))

        private val AGENT_COLOR: Color
            get() = getThemeColor("VersionControl.GitGreen", Color(0x43, 0xA0, 0x47), Color(0x66, 0xBB, 0x6A))

        internal val TOOL_COLOR: Color
            get() = getThemeColor("EditorTabs.selectedForeground", Color(0xAE, 0xA0, 0xDC), Color(0xB4, 0xA0, 0xDC))

        internal val THINK_COLOR: Color
            get() = getThemeColor("Label.disabledForeground", Color(0x80, 0x80, 0x80), Color(0xB0, 0xB0, 0xB0))

        private val ERROR_COLOR: Color
            get() = getThemeColor("Label.errorForeground", Color(0xC7, 0x22, 0x22), Color(0xE0, 0x60, 0x60))

        // Sub-agent theme colors — use distinct hues that work in both light/dark
        private val SA_EXPLORE_COLOR: Color get() = JBColor(Color(0x5B, 0xC0, 0xDE), Color(0x5B, 0xC0, 0xDE))
        private val SA_TASK_COLOR: Color get() = JBColor(Color(0xF0, 0xAD, 0x4E), Color(0xF0, 0xAD, 0x4E))
        private val SA_GENERAL_COLOR: Color get() = JBColor(Color(0x56, 0x9C, 0xD6), Color(0x56, 0x9C, 0xD6))
        private val SA_REVIEW_COLOR: Color get() = JBColor(Color(0xDA, 0xA5, 0x20), Color(0xDA, 0xA5, 0x20))
        private val SA_UI_COLOR: Color get() = JBColor(Color(0xD8, 0x70, 0x93), Color(0xD8, 0x70, 0x93))

        internal const val ICON_ERROR = "\u274C"

        /** Matches `[quick-reply: Option A | Option B | ...]` tags on their own line. */
        val QUICK_REPLY_TAG_REGEX = Regex("""^\[quick-reply:\s*([^\]]+)]\s*$""", RegexOption.MULTILINE)

        /** Human-readable name and short description for each tool */
        internal data class ToolInfo(val displayName: String, val description: String)

        /** Sub-agent display name lookup (icon removed — color is per-instance now) */
        internal data class SubAgentInfo(val displayName: String)

        private const val AGENT_TYPE_GENERAL = "general-purpose"
        private const val SA_COLOR_COUNT = 8

        private const val AGENT_ROW_OPEN = "<div class='agent-row'><div class='agent-bubble'>"
        private const val DIV_CLOSE_2 = "</div></div>"

        internal val SUB_AGENT_INFO = mapOf(
            "explore" to SubAgentInfo("Explore Agent"),
            "task" to SubAgentInfo("Task Agent"),
            AGENT_TYPE_GENERAL to SubAgentInfo("General Agent"),
            "code-review" to SubAgentInfo("Code Review Agent"),
            "ui-reviewer" to SubAgentInfo("UI Review Agent"),
        )

        /** JSON key to use as subtitle in the chip label for specific tools */
        private val TOOL_SUBTITLE_KEY = mapOf(
            // File operations
            "read_file" to "path",
            "intellij_read_file" to "path",
            "write_file" to "path",
            "intellij_write_file" to "path",
            "create_file" to "path",
            "delete_file" to "path",
            "open_in_editor" to "file",
            "show_diff" to "file",
            "get_file_outline" to "path",
            "format_code" to "path",
            "optimize_imports" to "path",
            // Code navigation
            "search_symbols" to "query",
            "find_references" to "symbol",
            "go_to_declaration" to "symbol",
            "get_type_hierarchy" to "symbol",
            "get_documentation" to "symbol",
            "search_text" to "query",
            // Tests
            "run_tests" to "target",
            // Git
            "git_blame" to "path",
            "git_commit" to "message",
            "git_branch" to "name",
            // Code quality
            "run_inspections" to "scope",
            "apply_quickfix" to "inspection_id",
            "add_to_dictionary" to "word",
            // Run configs
            "run_configuration" to "name",
            "create_run_configuration" to "name",
            "edit_run_configuration" to "name",
            // Shell & infrastructure
            "run_command" to "title",
            "http_request" to "url",
            // Agent meta
            "report_intent" to "intent",
            "task" to "description",
            // Built-in CLI tools
            "view" to "path",
            "edit" to "path",
            "create" to "path",
            "grep" to "pattern",
            "glob" to "pattern",
            "bash" to "description",
            "web_search" to "query",
            "web_fetch" to "url",
        )

        /** Tools whose arguments contain markdown content to write to a file and link */
        private val TOOL_CONTENT_KEY = mapOf(
            "update_todo" to "todos",
        )

        internal val TOOL_DISPLAY_INFO = mapOf(
            // Code Navigation
            "search_symbols" to ToolInfo(
                "Search Symbols",
                "Search for classes, methods, and fields across the project"
            ),
            "get_file_outline" to ToolInfo(
                "File Outline",
                "Get the structure outline of a file (classes, methods, fields)"
            ),
            "find_references" to ToolInfo("Find References", "Find all usages of a symbol across the project"),
            "list_project_files" to ToolInfo("List Project Files", "List files and directories in the project tree"),
            // File Operations
            "read_file" to ToolInfo("Read File", "Read the contents of a file"),
            "intellij_read_file" to ToolInfo("Read File", "Read the contents of a file via IntelliJ"),
            "write_file" to ToolInfo("Write File", "Write or overwrite the contents of a file"),
            "intellij_write_file" to ToolInfo("Write File", "Write or overwrite file contents via IntelliJ"),
            "create_file" to ToolInfo("Create File", "Create a new file with the given content"),
            "delete_file" to ToolInfo("Delete File", "Delete a file from the project"),
            // Code Quality
            "get_problems" to ToolInfo("Get Problems", "Get current problems/warnings from the Problems panel"),
            "get_highlights" to ToolInfo("Get Highlights", "Get cached editor highlights for open files"),
            "run_inspections" to ToolInfo("Run Inspections", "Run the full IntelliJ inspection engine on the project"),
            "get_compilation_errors" to ToolInfo(
                "Compilation Errors",
                "Fast compilation error check using cached daemon results"
            ),
            "apply_quickfix" to ToolInfo("Apply Quick Fix", "Apply an IntelliJ quick-fix to resolve an issue"),
            "suppress_inspection" to ToolInfo("Suppress Inspection", "Suppress an inspection warning"),
            "optimize_imports" to ToolInfo("Optimize Imports", "Remove unused imports and organize remaining ones"),
            "format_code" to ToolInfo("Format Code", "Reformat code according to project style settings"),
            "add_to_dictionary" to ToolInfo("Add to Dictionary", "Add a word to the spell-check dictionary"),
            "run_qodana" to ToolInfo("Run Qodana", "Run Qodana static analysis on the project"),
            "run_sonarqube_analysis" to ToolInfo("Run SonarQube", "Run SonarQube for IDE analysis on the project"),
            // Refactoring
            "refactor" to ToolInfo("Refactor", "Refactor code (rename, extract method, inline, safe delete)"),
            "go_to_declaration" to ToolInfo("Go to Declaration", "Navigate to the declaration of a symbol"),
            "get_type_hierarchy" to ToolInfo("Type Hierarchy", "Show supertypes and subtypes of a class"),
            "get_documentation" to ToolInfo("Get Documentation", "Retrieve documentation for a symbol"),
            // Tests
            "list_tests" to ToolInfo("List Tests", "List available test classes and methods"),
            "run_tests" to ToolInfo("Run Tests", "Execute tests and return results"),
            "get_test_results" to ToolInfo("Test Results", "Get results from the last test run"),
            "get_coverage" to ToolInfo("Get Coverage", "Get code coverage data from the last run"),
            // Git
            "git_status" to ToolInfo("Git Status", "Show working tree status"),
            "git_diff" to ToolInfo("Git Diff", "Show changes between commits, working tree, etc."),
            "git_log" to ToolInfo("Git Log", "Show commit history"),
            "git_blame" to ToolInfo("Git Blame", "Show line-by-line authorship of a file"),
            "git_commit" to ToolInfo("Git Commit", "Record changes to the repository"),
            "git_stage" to ToolInfo("Git Stage", "Stage files for commit"),
            "git_unstage" to ToolInfo("Git Unstage", "Unstage files from the index"),
            "git_branch" to ToolInfo("Git Branch", "List, create, or switch branches"),
            "git_stash" to ToolInfo("Git Stash", "Stash changes in working directory"),
            "git_show" to ToolInfo("Git Show", "Show details of a commit"),
            // Project
            "get_project_info" to ToolInfo("Project Info", "Get project name, SDK, modules, and settings"),
            "build_project" to ToolInfo("Build Project", "Trigger incremental compilation of the project"),
            "get_indexing_status" to ToolInfo("Indexing Status", "Check if IntelliJ is currently indexing"),
            "download_sources" to ToolInfo("Download Sources", "Download source jars for dependencies"),
            // Infrastructure
            "http_request" to ToolInfo("HTTP Request", "Make an HTTP request"),
            "run_command" to ToolInfo("Run Command", "Run a shell command"),
            "read_ide_log" to ToolInfo("Read IDE Log", "Read recent entries from the IDE log"),
            "get_notifications" to ToolInfo("Get Notifications", "Get IDE notification messages"),
            "read_run_output" to ToolInfo("Read Run Output", "Read output from a run configuration"),
            // Terminal
            "run_in_terminal" to ToolInfo("Run in Terminal", "Run a command in the IDE terminal"),
            "read_terminal_output" to ToolInfo("Terminal Output", "Read output from a terminal session"),
            "list_terminals" to ToolInfo("List Terminals", "List active terminal sessions"),
            // Editor
            "open_in_editor" to ToolInfo("Open in Editor", "Open a file in the editor"),
            "show_diff" to ToolInfo("Show Diff", "Show a diff view between two contents"),
            "create_scratch_file" to ToolInfo("Create Scratch", "Create a scratch file for quick experiments"),
            "list_scratch_files" to ToolInfo("List Scratches", "List available scratch files"),
            // Run Configurations
            "list_run_configurations" to ToolInfo("List Run Configs", "List available run/debug configurations"),
            "run_configuration" to ToolInfo("Run Configuration", "Execute a run/debug configuration"),
            "create_run_configuration" to ToolInfo("Create Run Config", "Create a new run/debug configuration"),
            "edit_run_configuration" to ToolInfo("Edit Run Config", "Modify an existing run/debug configuration"),
            // Display / Presentation
            "show_file" to ToolInfo("Show File", "Display a file to the user"),
            // Agent Meta
            "update_todo" to ToolInfo("Update TODO", "Update the agent's task checklist"),
            "report_intent" to ToolInfo("Intent", "Report current task intent"),
            "task" to ToolInfo("Sub-Agent Task", "Launch a specialized sub-agent for a task"),
            // Built-in CLI tools (Copilot agent)
            "view" to ToolInfo("View", "View file or directory contents"),
            "edit" to ToolInfo("Edit", "Make string replacements in a file"),
            "create" to ToolInfo("Create", "Create a new file"),
            "grep" to ToolInfo("Grep", "Search file contents with ripgrep"),
            "glob" to ToolInfo("Glob", "Find files by name pattern"),
            "bash" to ToolInfo("Bash", "Run a shell command"),
            "read_bash" to ToolInfo("Read Bash", "Read output from an async shell command"),
            "write_bash" to ToolInfo("Write Bash", "Send input to an async shell command"),
            "stop_bash" to ToolInfo("Stop Bash", "Stop a running shell command"),
            "list_bash" to ToolInfo("List Bash", "List active shell sessions"),
            "web_search" to ToolInfo("Web Search", "AI-powered web search with citations"),
            "web_fetch" to ToolInfo("Fetch URL", "Fetch a web page and return its content"),
            // GitHub MCP tools
            "actions_get" to ToolInfo("GitHub Actions", "Get details about a GitHub Actions resource"),
            "actions_list" to ToolInfo("GitHub Actions", "List GitHub Actions workflows, runs, or jobs"),
            "get_commit" to ToolInfo("Get Commit", "Get details for a GitHub commit"),
            "get_file_contents" to ToolInfo("Get File", "Get file contents from a GitHub repository"),
            "get_job_logs" to ToolInfo("Job Logs", "Get logs for GitHub Actions workflow jobs"),
            "issue_read" to ToolInfo("Read Issue", "Get information about a GitHub issue"),
            "list_branches" to ToolInfo("List Branches", "List branches in a GitHub repository"),
            "list_commits" to ToolInfo("List Commits", "List commits in a GitHub repository"),
            "list_issues" to ToolInfo("List Issues", "List issues in a GitHub repository"),
            "list_pull_requests" to ToolInfo("List PRs", "List pull requests in a GitHub repository"),
            "pull_request_read" to ToolInfo("Read PR", "Get information about a GitHub pull request"),
            "search_code" to ToolInfo("Search Code", "Search code across GitHub repositories"),
            "search_issues" to ToolInfo("Search Issues", "Search for GitHub issues"),
            "search_pull_requests" to ToolInfo("Search PRs", "Search for GitHub pull requests"),
            "search_repositories" to ToolInfo("Search Repos", "Search for GitHub repositories"),
            "search_users" to ToolInfo("Search Users", "Search for GitHub users"),
            // IntelliJ extras
            "get_class_outline" to ToolInfo("Class Outline", "Show constructors, methods, and fields of a class"),
            "search_text" to ToolInfo("Search Text", "Search text or regex patterns across project files"),
            "undo" to ToolInfo("Undo", "Undo last edit action on a file"),
        )

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

        private fun escapeJs(s: String): String = s
            .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

        /** Sanitize an ID string for safe use as a DOM element id */
        private fun domId(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        /** Format current time as HH:mm for timestamps */
        private fun timestamp(): String {
            val cal = Calendar.getInstance()
            return "%02d:%02d".format(cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE])
        }

        private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
        private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"
    }

    // --- Data model ---
    internal sealed class EntryData {
        class Prompt(val text: String) : EntryData()
        class Text(val raw: StringBuilder = StringBuilder()) : EntryData()
        class Thinking(val raw: StringBuilder = StringBuilder()) : EntryData()
        class ToolCall(val title: String, val arguments: String? = null) : EntryData()
        class SubAgent(
            val agentType: String,
            val description: String,
            val prompt: String? = null,
            var result: String? = null,
            var status: String? = null,
            var colorIndex: Int = 0,
            val callId: String? = null
        ) : EntryData()

        class ContextFiles(val files: List<Pair<String, String>>) : EntryData()
        class Status(val icon: String, val message: String) : EntryData()
        class SessionSeparator(val timestamp: String) : EntryData()
    }

    private val entries = mutableListOf<EntryData>()
    private var currentTextData: EntryData.Text? = null
    private var currentThinkingData: EntryData.Thinking? = null
    private var entryCounter = 0
    private var thinkingCounter = 0
    private var contextCounter = 0
    private var nextSubAgentColor = 0
    private var activeSubAgentWrapperId: String? = null
    private var pendingAgentMetaId: String? = null

    // JCEF
    private val browser: JBCefBrowser?
    private val openFileQuery: JBCefJSQuery?
    private var browserReady = false
    private val pendingJs = mutableListOf<String>()
    private var cursorBridgeJs = ""
    private var openUrlBridgeJs = ""
    private var loadMoreBridgeJs = ""
    private var quickReplyBridgeJs = ""
    var onQuickReply: ((String) -> Unit)? = null
    private val deferredRestoreJson = mutableListOf<com.google.gson.JsonElement>()
    private var deferredIdCounter = 0

    // Swing fallback
    private val fallbackArea: JBTextArea?

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            // Set initial browser background to match the IDE tool window background
            val panelBg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
            browser.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
            openFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openFileQuery.addHandler { handleFileLink(it); null }
            Disposer.register(this, openFileQuery)
            Disposer.register(this, browser)

            // URL bridge: JS notifies Kotlin to open external URLs in the system browser
            val openUrlQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openUrlQuery.addHandler { url ->
                com.intellij.ide.BrowserUtil.browse(url)
                null
            }
            Disposer.register(this, openUrlQuery)
            openUrlBridgeJs = openUrlQuery.inject("url")

            // Cursor bridge: JS notifies Swing of cursor changes
            val cursorQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            cursorQuery.addHandler { cursorType ->
                SwingUtilities.invokeLater {
                    browser.component.cursor = when (cursorType) {
                        "pointer" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        "text" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
                        else -> java.awt.Cursor.getDefaultCursor()
                    }
                }
                null
            }
            Disposer.register(this, cursorQuery)
            cursorBridgeJs = cursorQuery.inject("c")

            // Lazy-load bridge: JS calls Kotlin to load more deferred entries
            val loadMoreQueryBridge = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            loadMoreQueryBridge.addHandler { loadMoreEntries(); null }
            Disposer.register(this, loadMoreQueryBridge)
            loadMoreBridgeJs = loadMoreQueryBridge.inject("'load'")

            // Quick-reply bridge: JS calls Kotlin when user clicks a quick-reply button
            val quickReplyQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            quickReplyQuery.addHandler { text -> onQuickReply?.invoke(text); null }
            Disposer.register(this, quickReplyQuery)
            quickReplyBridgeJs = quickReplyQuery.inject("text")

            add(browser.component, BorderLayout.CENTER)

            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        SwingUtilities.invokeLater {
                            browserReady = true
                            pendingJs.forEach { browser.cefBrowser.executeJavaScript(it, "", 0) }
                            pendingJs.clear()
                        }
                    }
                }
            }, browser.cefBrowser)

            browser.loadHTML(buildInitialPage())
            fallbackArea = null

            // Listen for IDE theme changes and update CSS variables
            val connection = ApplicationManager.getApplication().messageBus.connect(this)
            connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { updateThemeColors() })
        } else {
            browser = null; openFileQuery = null
            fallbackArea = JBTextArea().apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
            }
            add(JBScrollPane(fallbackArea), BorderLayout.CENTER)
        }
    }

    // --- Public API ---

    fun addPromptEntry(text: String, contextFiles: List<Triple<String, String, Int>>? = null) {
        finalizeCurrentText()
        collapseThinking()
        entries.add(EntryData.Prompt(text))
        val ts = timestamp()
        val ctxChips = if (!contextFiles.isNullOrEmpty()) {
            contextFiles.joinToString("") { (name, path, line) ->
                val href = if (line > 0) "openfile://$path:$line" else "openfile://$path"
                "<a class='prompt-ctx-chip' href='$href' title='${escapeHtml(path)}${if (line > 0) ":$line" else ""}'>\uD83D\uDCC4 ${
                    escapeHtml(
                        name
                    )
                }</a>"
            }
        } else ""
        val html =
            "<div class='prompt-row'><div class='meta'><span class='ts'>$ts</span>$ctxChips</div><div class='prompt-bubble' tabindex='0' role='button' title='Click to show timestamp' onclick='toggleMeta(this)' onkeydown='if(event.key===\"Enter\"||event.key===\" \")this.click()'>${
                escapeHtml(text)
            }</div></div>"
        appendHtml(html)
        // Force scroll to bottom when user sends a new prompt
        executeJs("autoScroll=true;setTimeout(function(){window.scrollTo(0,document.body.scrollHeight)},50)")
        fallbackArea?.let { SwingUtilities.invokeLater { it.append(">>> $text\n") } }
    }

    /** Show model/multiplier on the prompt bubble immediately when sending */
    fun setPromptStats(modelId: String, multiplier: String) {
        val shortModel = escapeJs(modelId.substringAfterLast("/").take(30))
        val mult = escapeJs(multiplier)
        executeJs(
            """
            (function(){
              var prs=document.querySelectorAll('.prompt-row');
              var pr=prs.length?prs[prs.length-1]:null;
              if(!pr)return;
              var pm=pr.querySelector('.meta');
              if(!pm)return;
              pm.classList.add('show');
              var sc=document.createElement('span');
              sc.className='turn-chip stats';sc.id='turn-stats';
              sc.textContent='$mult';
              sc.setAttribute('data-tip','$shortModel');
              pm.appendChild(sc);
            })()
        """.trimIndent()
        )
    }

    /** Adds a collapsible context files section showing attached file names/paths. */
    fun addContextFilesEntry(files: List<Pair<String, String>>) {
        entries.add(EntryData.ContextFiles(files))
        // Context files are now shown as chips in the prompt bubble via addPromptEntry
    }

    fun appendThinkingText(text: String) {
        if (currentThinkingData == null) {
            currentThinkingData = EntryData.Thinking().also { entries.add(it) }
            thinkingCounter++
            val html = """<div class='collapse-section thinking-section' id='think-$thinkingCounter'>
                <div class='collapse-header' tabindex='0' role='button' aria-expanded='true' onclick='toggleThinking("think-$thinkingCounter")' onkeydown='if(event.key==="Enter"||event.key===" ")this.click()'>
                    <span class='collapse-icon thinking-pulse'>💭</span>
                    <span class='collapse-label'>Thinking...</span>
                    <span class='caret'>▾</span>
                </div>
                <div class='collapse-content'></div>
            </div>"""
            insertHtmlBeforePendingBubble(html)
        }
        currentThinkingData!!.raw.append(text)
        val id = "think-$thinkingCounter"
        executeJs(
            "(function(){var e=document.querySelector('#$id .collapse-content');if(e){e.textContent+='${
                escapeJs(text)
            }';scrollIfNeeded();}})()"
        )
    }

    fun collapseThinking() {
        if (currentThinkingData == null) return
        currentThinkingData = null
        val id = "think-$thinkingCounter"
        executeJs(
            """(function(){var el=document.getElementById('$id');if(!el)return;
            el.classList.add('collapsed');
            el.querySelector('.collapse-icon').classList.remove('thinking-pulse');
            el.querySelector('.collapse-label').textContent='Thought process';
            el.querySelector('.caret').textContent='▸';
            collapseThinkingToChip('$id');})()"""
        )
    }

    fun appendText(text: String) {
        collapseThinking()
        activeSubAgentWrapperId = null
        // Skip empty/blank chunks before the bubble exists to avoid rendering an empty bubble
        if (currentTextData == null && text.isBlank()) return
        if (currentTextData == null) {
            currentTextData = EntryData.Text().also { entries.add(it) }
            if (pendingAgentMetaId != null) {
                // Reuse the "Working…" bubble — replace placeholder with streaming content
                pendingAgentMetaId = null
                val id = "text-$entryCounter"
                executeJs("(function(){var e=document.getElementById('$id');if(e){e.innerHTML='<pre class=\"streaming\"></pre>';}})()")
            } else {
                entryCounter++
                val ts = timestamp()
                val html =
                    "<div class='agent-row'><div class='meta' id='meta-$entryCounter'><span class='ts'>$ts</span></div><div class='agent-bubble' id='text-$entryCounter' onclick='toggleMeta(this)'><pre class='streaming'></pre></div></div>"
                appendHtml(html)
            }
            executeJs("collapsePendingTools()")
        }
        currentTextData!!.raw.append(text)
        val id = "text-$entryCounter"
        executeJs("(function(){var e=document.querySelector('#$id .streaming');if(e){e.textContent+='${escapeJs(text)}';scrollIfNeeded();}})()")
        fallbackArea?.let { SwingUtilities.invokeLater { it.append(text) } }
    }

    fun addToolCallEntry(id: String, title: String, arguments: String? = null) {
        finalizeCurrentText()
        entries.add(EntryData.ToolCall(title, arguments))
        val did = domId(id)
        val baseName = title.substringAfterLast("-")
        val info = TOOL_DISPLAY_INFO[title] ?: TOOL_DISPLAY_INFO[baseName]
        var displayName = info?.displayName ?: baseName.replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val subtitleKey = TOOL_SUBTITLE_KEY[title] ?: TOOL_SUBTITLE_KEY[baseName]
        if (subtitleKey != null && !arguments.isNullOrBlank()) {
            try {
                val json = com.google.gson.JsonParser.parseString(arguments).asJsonObject
                json[subtitleKey]?.asString?.let { raw ->
                    val short = when {
                        raw.contains('/') -> raw.substringAfterLast('/')
                        raw.length > 40 -> raw.take(37) + "\u2026"
                        else -> raw
                    }
                    displayName = "$displayName: $short"
                }
            } catch (_: Exception) {
                // subtitle extraction is best-effort
            }
        }
        val safeDisplayName = escapeHtml(displayName)

        val contentParts = StringBuilder()
        if (info?.description != null) {
            contentParts.append("<div class='tool-desc'>${escapeHtml(info.description)}</div>")
        }
        val contentKey = TOOL_CONTENT_KEY[title] ?: TOOL_CONTENT_KEY[baseName]
        if (contentKey != null && !arguments.isNullOrBlank()) {
            val filePath = writeToolContentFile(title, contentKey, arguments)
            if (filePath != null) {
                contentParts.append(
                    "<div class='tool-params-label'><a href='openfile://$filePath'>📋 Open in editor</a></div>"
                )
            }
        } else if (!arguments.isNullOrBlank()) {
            contentParts.append(
                "<div class='tool-params-label'>Parameters:</div><pre class='tool-params'><code>${
                    escapeHtml(
                        arguments
                    )
                }</code></pre>"
            )
        }
        contentParts.append("<div class='tool-result' id='result-$did'><span class='tool-result-pending'>Running...</span></div>")

        // Tool section is hidden from the start; a chip on the agent bubble represents it
        val html = """<div class='collapse-section tool-section collapsed turn-hidden' id='tool-$did'>
            <div class='collapse-header' tabindex='0' role='button' aria-expanded='false' onclick='toggleTool("tool-$did")' onkeydown='if(event.key==="Enter"||event.key===" ")this.click()'>
                <span class='collapse-icon'><span class='tool-spinner'></span></span>
                <span class='collapse-label'>$safeDisplayName</span>
                <span class='caret'>▸</span>
            </div>
            <div class='collapse-content'>$contentParts</div></div>"""

        val wrapperId = activeSubAgentWrapperId
        val metaId: String
        if (wrapperId != null) {
            appendHtmlToSubAgent(html, wrapperId)
            metaId = "meta-$wrapperId"
        } else {
            // Ensure a "Working…" agent bubble exists for chips
            if (pendingAgentMetaId == null) {
                entryCounter++
                val ts = timestamp()
                val metaIdVal = "meta-$entryCounter"
                pendingAgentMetaId = metaIdVal
                val bubbleHtml =
                    "<div class='agent-row'><div class='meta show' id='$metaIdVal'><span class='ts'>$ts</span></div>" +
                        "<div class='agent-bubble' id='text-$entryCounter' onclick='toggleMeta(this)'>" +
                        "<span class='agent-pending'>Working\u2026</span></div></div>"
                appendHtml(bubbleHtml)
            }
            metaId = pendingAgentMetaId!!
            insertHtmlBeforePendingBubble(html)
        }
        // Create chip directly on the meta container
        executeJs("addToolChipDirect('tool-$did','${escapeJs(safeDisplayName)}','$metaId')")
        fallbackArea?.let { SwingUtilities.invokeLater { it.append("⚒ $displayName\n") } }
    }

    fun updateToolCall(id: String, status: String, details: String? = null) {
        val did = domId(id)
        val resultContent = if (!details.isNullOrBlank()) {
            "<div class='tool-result-label'>Output:</div><pre class='tool-output'><code>${escapeHtml(details)}</code></pre>"
        } else {
            if (status == "completed") "✓ Completed" else "✖ Failed"
        }
        val encoded = Base64.getEncoder().encodeToString(resultContent.toByteArray(Charsets.UTF_8))
        val failed = status == "failed"
        val iconHtml = if (failed) "✖" else "✓"
        val colorJs =
            if (failed) "icon.style.color='red';el.querySelector('.collapse-label').style.color='red';" else ""
        executeJs(
            """(function(){var el=document.getElementById('tool-$did');if(!el)return;
            var icon=el.querySelector('.collapse-icon');icon.innerHTML='$iconHtml';$colorJs
            var r=document.getElementById('result-$did');if(r)r.innerHTML=b64('$encoded');
            updateToolChipStatus('tool-$did',$failed);})()"""
        )
    }

    fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialResult: String? = null, initialStatus: String? = null
    ) {
        finalizeCurrentText()
        val colorIndex = nextSubAgentColor++ % SA_COLOR_COUNT
        val entry = EntryData.SubAgent(agentType, description, prompt, colorIndex = colorIndex, callId = id)
        entry.result = initialResult
        entry.status = initialStatus
        entries.add(entry)
        val did = domId(id)
        val info = SUB_AGENT_INFO[agentType] ?: SubAgentInfo(
            agentType.replaceFirstChar { it.uppercase() } + " Agent")
        val safeName = escapeHtml(info.displayName)
        val cssClass = "subagent-c$colorIndex"

        val wrapperId = "sa-$did"
        val sb = StringBuilder()
        sb.append("<div class='$cssClass' id='$wrapperId'>")

        // Render prompt as a green agent bubble with colored @Agent prefix (no badge — result bubble shows status)
        if (!prompt.isNullOrBlank()) {
            sb.append(AGENT_ROW_OPEN)
            sb.append("<span class='subagent-prefix'>@$safeName</span>")
            sb.append(" ${escapeHtml(prompt)}")
            sb.append(DIV_CLOSE_2)
        } else {
            sb.append(AGENT_ROW_OPEN)
            sb.append("<span class='subagent-prefix'>@$safeName</span>")
            sb.append(" ${escapeHtml(description)}")
            sb.append(DIV_CLOSE_2)
        }

        // Response bubble in instance-specific color with meta for tool chips
        sb.append("<div class='agent-row'><div class='meta' id='meta-$wrapperId'></div>")
        sb.append("<div class='subagent-bubble' id='result-$did' onclick='toggleMeta(this)'>")
        when {
            !initialResult.isNullOrBlank() -> sb.append(markdownToHtml(initialResult))
            initialStatus == "completed" -> sb.append("Completed")
            initialStatus == "failed" -> sb.append("<span style='color:var(--error)'>\u2716 Failed</span>")
            else -> sb.append("<span class='subagent-pending'>Working...</span>")
        }
        sb.append("</div></div>")

        sb.append("</div>")
        appendHtml(sb.toString())
        activeSubAgentWrapperId = wrapperId
        fallbackArea?.let { SwingUtilities.invokeLater { it.append("${info.displayName}: $description\n") } }
    }

    fun updateSubAgentResult(id: String, status: String, result: String?) {
        // Don't clear activeSubAgentWrapperId here — it's cleared when main agent text starts
        // Persist result in entry data so it survives serialization/restore
        // Match by callId to handle parallel sub-agents correctly
        val entry = entries.filterIsInstance<EntryData.SubAgent>().find { it.callId == id }
            ?: entries.filterIsInstance<EntryData.SubAgent>().lastOrNull()
        entry?.let {
            it.result = result
            it.status = status
        }
        val did = domId(id)
        val resultHtml = if (!result.isNullOrBlank()) {
            markdownToHtml(result)
        } else {
            if (status == "completed") "Completed" else "<span style='color:var(--error)'>✖ Failed</span>"
        }
        val encoded = Base64.getEncoder().encodeToString(resultHtml.toByteArray(Charsets.UTF_8))
        executeJs(
            """(function(){var r=document.getElementById('result-$did');if(r){r.innerHTML=b64('$encoded');}
            scrollIfNeeded();})()"""
        )
    }

    fun addErrorEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status(ICON_ERROR, message))
        appendHtml("<div class='status-row error'>$ICON_ERROR ${escapeHtml(message)}</div>")
    }

    fun addInfoEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status("\u2139", message))
        appendHtml("<div class='status-row info'>\u2139 ${escapeHtml(message)}</div>")
    }

    fun hasContent(): Boolean = entries.isNotEmpty()

    /** Adds a visual separator marking previous session content as stale */
    fun addSessionSeparator(timestamp: String) {
        finalizeCurrentText()
        entries.add(EntryData.SessionSeparator(timestamp))
        val html =
            "<div class='session-sep'><span class='session-sep-line'></span><span class='session-sep-label'>New session \uD83D\uDCC5 ${
                escapeHtml(timestamp)
            }</span><span class='session-sep-line'></span></div>"
        appendHtml(html)
    }

    fun showPlaceholder(text: String) {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; entryCounter = 0; thinkingCounter =
            0; contextCounter = 0; nextSubAgentColor = 0
        executeJs("document.getElementById('container').innerHTML='<div class=\"placeholder\">${escapeJs(escapeHtml(text))}</div>'")
        fallbackArea?.let { SwingUtilities.invokeLater { it.text = text } }
    }

    fun clear() {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; entryCounter = 0; thinkingCounter =
            0; contextCounter = 0; nextSubAgentColor = 0
        executeJs("document.getElementById('container').innerHTML=''")
        fallbackArea?.let { SwingUtilities.invokeLater { it.text = "" } }
    }

    /** Serialize conversation entries to JSON for persistence */
    fun serializeEntries(): String {
        val arr = com.google.gson.JsonArray()
        for (e in entries) {
            val obj = com.google.gson.JsonObject()
            when (e) {
                is EntryData.Prompt -> {
                    obj.addProperty("type", "prompt"); obj.addProperty("text", e.text)
                }

                is EntryData.Text -> {
                    obj.addProperty("type", "text"); obj.addProperty("raw", e.raw.toString())
                }

                is EntryData.Thinking -> {
                    obj.addProperty("type", "thinking"); obj.addProperty("raw", e.raw.toString())
                }

                is EntryData.ToolCall -> {
                    obj.addProperty("type", "tool"); obj.addProperty("title", e.title); obj.addProperty(
                        "args",
                        e.arguments ?: ""
                    )
                }

                is EntryData.SubAgent -> {
                    obj.addProperty("type", "subagent")
                    obj.addProperty("agentType", e.agentType)
                    obj.addProperty("description", e.description)
                    obj.addProperty("prompt", e.prompt ?: "")
                    obj.addProperty("result", e.result ?: "")
                    obj.addProperty("status", e.status ?: "")
                    obj.addProperty("colorIndex", e.colorIndex)
                }

                is EntryData.ContextFiles -> {
                    obj.addProperty("type", "context");
                    val fa = com.google.gson.JsonArray(); e.files.forEach { f ->
                        val fo = com.google.gson.JsonObject(); fo.addProperty("name", f.first); fo.addProperty(
                        "path",
                        f.second
                    ); fa.add(fo)
                    }; obj.add("files", fa)
                }

                is EntryData.Status -> {
                    obj.addProperty("type", "status"); obj.addProperty("icon", e.icon); obj.addProperty(
                        "message",
                        e.message
                    )
                }

                is EntryData.SessionSeparator -> {
                    obj.addProperty("type", "separator"); obj.addProperty("timestamp", e.timestamp)
                }
            }
            arr.add(obj)
        }
        return arr.toString()
    }

    /** Restore conversation entries from JSON and rebuild the chat view */
    fun restoreEntries(json: String) {
        try {
            val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
            // Find split point: show last N complete prompt/response turns
            val turnsToShow = 5
            val splitAt = findSplitAtNthPromptFromEnd(arr, turnsToShow)
            if (splitAt <= 0) {
                restoreAndRenderEntries(arr)
                return
            }
            // Phase 1: Add older entries to data model only (no rendering)
            for (i in 0 until splitAt) {
                deferredRestoreJson.add(arr[i])
                addEntryDataOnly(arr[i].asJsonObject)
            }
            // Phase 2: Show clickable "load more" banner
            val bannerHtml =
                "<div id='load-more-sentinel' class='load-more-banner' onclick='loadMore()' style='cursor:pointer'>" +
                    "<span class='load-more-text'>\u25B2 Load earlier messages (${deferredRestoreJson.size} more) \u2014 click or scroll up</span></div>"
            appendHtml(bannerHtml)
            // Phase 3: Render recent entries
            for (i in splitAt until arr.size()) {
                renderRestoredEntry(arr[i].asJsonObject)
            }
            executeJs("finalizeTurn({})")
            // Phase 4: Set up IntersectionObserver + click handler for loading
            executeJs(
                """(function(){
                var sentinel=document.getElementById('load-more-sentinel');
                if(!sentinel)return;
                var obs=new IntersectionObserver(function(entries){
                    if(entries[0].isIntersecting&&!window._loadingMore){loadMore();}
                },{threshold:0.1});
                obs.observe(sentinel);
            })()"""
            )
        } catch (_: Exception) { /* best-effort restore */
        }
    }

    /** Walk backwards from the end to find the Nth prompt entry, then return its index as the split point. */
    private fun findSplitAtNthPromptFromEnd(arr: com.google.gson.JsonArray, n: Int): Int {
        var promptCount = 0
        for (i in arr.size() - 1 downTo 0) {
            if (arr[i].asJsonObject["type"]?.asString == "prompt") {
                promptCount++
                if (promptCount >= n) return i
            }
        }
        return 0
    }

    private fun findSplitAtNthPromptFromEnd(list: List<com.google.gson.JsonElement>, n: Int): Int {
        var promptCount = 0
        for (i in list.size - 1 downTo 0) {
            if (list[i].asJsonObject["type"]?.asString == "prompt") {
                promptCount++
                if (promptCount >= n) return i
            }
        }
        return 0
    }

    private fun addEntryDataOnly(obj: com.google.gson.JsonObject) {
        when (obj["type"]?.asString) {
            "prompt" -> entries.add(EntryData.Prompt(obj["text"]?.asString ?: ""))
            "text" -> entries.add(EntryData.Text(StringBuilder(obj["raw"]?.asString ?: "")))
            "thinking" -> entries.add(EntryData.Thinking(StringBuilder(obj["raw"]?.asString ?: "")))
            "tool" -> entries.add(
                EntryData.ToolCall(
                    obj["title"]?.asString ?: "",
                    obj["args"]?.asString?.ifEmpty { null })
            )

            "subagent" -> entries.add(
                EntryData.SubAgent(
                    obj["agentType"]?.asString ?: AGENT_TYPE_GENERAL,
                    obj["description"]?.asString ?: "",
                    obj["prompt"]?.asString?.ifEmpty { null },
                    obj["result"]?.asString?.ifEmpty { null },
                    obj["status"]?.asString?.ifEmpty { null },
                    obj["colorIndex"]?.asInt ?: 0
                )
            )

            "context" -> {
                val files = obj["files"]?.asJsonArray?.map {
                    val f = it.asJsonObject; Pair(f["name"]?.asString ?: "", f["path"]?.asString ?: "")
                } ?: emptyList()
                entries.add(EntryData.ContextFiles(files))
            }

            "status" -> entries.add(
                EntryData.Status(
                    obj["icon"]?.asString ?: "",
                    obj["message"]?.asString ?: ""
                )
            )

            "separator" -> entries.add(EntryData.SessionSeparator(obj["timestamp"]?.asString ?: ""))
        }
    }

    private fun renderRestoredEntry(obj: com.google.gson.JsonObject) {
        when (obj["type"]?.asString) {
            "prompt" -> addPromptEntry(obj["text"]?.asString ?: "")
            "text" -> {
                appendText(obj["raw"]?.asString ?: ""); finalizeCurrentText()
            }

            "thinking" -> {
                appendThinkingText(obj["raw"]?.asString ?: ""); collapseThinking()
            }

            "tool" -> {
                entryCounter++
                val restoredId = "restored-${entryCounter}"
                addToolCallEntry(restoredId, obj["title"]?.asString ?: "", obj["args"]?.asString?.ifEmpty { null })
                updateToolCall(restoredId, "completed")
            }

            "subagent" -> {
                entryCounter++
                val restoredId = "restored-${entryCounter}"
                addSubAgentEntry(
                    restoredId,
                    obj["agentType"]?.asString ?: AGENT_TYPE_GENERAL,
                    obj["description"]?.asString ?: "Sub-agent task",
                    obj["prompt"]?.asString?.ifEmpty { null },
                    obj["result"]?.asString?.ifEmpty { null },
                    obj["status"]?.asString?.ifEmpty { null } ?: "completed"
                )
            }

            "context" -> {
                val files = obj["files"]?.asJsonArray?.map {
                    val f = it.asJsonObject; Pair(f["name"]?.asString ?: "", f["path"]?.asString ?: "")
                } ?: emptyList()
                addContextFilesEntry(files)
            }

            "status" -> {
                val icon = obj["icon"]?.asString ?: ""
                val msg = obj["message"]?.asString ?: ""
                if (icon == ICON_ERROR) addErrorEntry(msg) else addInfoEntry(msg)
            }

            "separator" -> addSessionSeparator(obj["timestamp"]?.asString ?: "")
        }
    }

    private fun restoreAndRenderEntries(arr: com.google.gson.JsonArray) {
        for (elem in arr) {
            renderRestoredEntry(elem.asJsonObject)
        }
        executeJs("finalizeTurn({})")
    }

    private fun loadMoreEntries() {
        if (deferredRestoreJson.isEmpty()) return
        // Load 3 complete prompt/response turns at a time
        val turnsToLoad = 3
        val start = findSplitAtNthPromptFromEnd(deferredRestoreJson, turnsToLoad)
        val batch = deferredRestoreJson.subList(start, deferredRestoreJson.size).toList()
        deferredRestoreJson.subList(start, deferredRestoreJson.size).clear()
        val remaining = deferredRestoreJson.size
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = renderBatchHtml(batch)
            val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
            SwingUtilities.invokeLater {
                executeJs(
                    """(function(){
                    var c=document.getElementById('container');
                    var sentinel=document.getElementById('load-more-sentinel');
                    var scrollH=document.body.scrollHeight;var scrollY=window.scrollY;
                    if(sentinel){sentinel.insertAdjacentHTML('afterend',b64('$encoded'));}
                    else{c.insertAdjacentHTML('afterbegin',b64('$encoded'));}
                    window.scrollTo(0,scrollY+(document.body.scrollHeight-scrollH));
                    finalizeTurn({});
                    window._loadingMore=false;
                    var seps=c.querySelectorAll('.session-sep');
                    for(var j=0;j<seps.length;j++){
                        var n=seps[j].nextElementSibling;var ok=false;
                        while(n&&!n.classList.contains('session-sep')){
                            if(n.classList.contains('prompt-row')||n.classList.contains('agent-row')||
                               n.classList.contains('status-row')){ok=true;break;}
                            n=n.nextElementSibling;
                        }
                        if(!ok)seps[j].remove();
                    }
                    ${
                        if (remaining == 0) "if(sentinel)sentinel.remove();"
                        else "var t=sentinel?sentinel.querySelector('.load-more-text'):null;" +
                            "if(t)t.textContent='\\u25B2 Load earlier messages ($remaining more)';"
                    }
                })()"""
                )
            }
        }
    }

    private fun renderBatchPrompt(obj: com.google.gson.JsonObject): String {
        val text = obj["text"]?.asString ?: ""
        return "<div class='prompt-row'><div class='meta'><span class='ts'></span></div>" +
            "<div class='prompt-bubble' tabindex='0' role='button' title='Click to show timestamp' onclick='toggleMeta(this)' onkeydown='if(event.key===\"Enter\"||event.key===\" \")this.click()'>${
                escapeHtml(
                    text
                )
            }</div></div>"
    }

    private fun renderBatchText(obj: com.google.gson.JsonObject): String {
        val raw = obj["raw"]?.asString ?: ""
        val html = markdownToHtml(raw)
        deferredIdCounter++
        return "<div class='agent-row'><div class='meta'><span class='ts'></span></div>" +
            "<div class='agent-bubble' onclick='toggleMeta(this)'>$html</div></div>"
    }

    private fun renderBatchThinking(obj: com.google.gson.JsonObject): String {
        deferredIdCounter++
        val raw = obj["raw"]?.asString ?: ""
        val id = "def-think-$deferredIdCounter"
        return "<div class='collapse-section thinking-section collapsed' id='$id'>" +
            "<div class='collapse-header' tabindex='0' role='button' aria-expanded='false' onclick='toggleThinking(\"$id\")' onkeydown='if(event.key===\"Enter\"||event.key===\" \")this.click()'>" +
            "<span class='collapse-icon'>\uD83D\uDCAD</span>" +
            "<span class='collapse-label'>Thought process</span>" +
            "<span class='caret'>\u25B8</span></div>" +
            "<div class='collapse-content'>${escapeHtml(raw)}</div></div>"
    }

    private fun renderBatchTool(obj: com.google.gson.JsonObject): String {
        deferredIdCounter++
        val title = obj["title"]?.asString ?: ""
        val args = obj["args"]?.asString?.ifEmpty { null }
        val baseName = title.substringAfterLast("-")
        val info = TOOL_DISPLAY_INFO[title] ?: TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replace("_", " ").replaceFirstChar { it.uppercase() }
        val id = "def-tool-$deferredIdCounter"
        val contentParts = StringBuilder()
        if (info?.description != null) {
            contentParts.append("<div class='tool-desc'>${escapeHtml(info.description)}</div>")
        }
        if (!args.isNullOrBlank()) {
            contentParts.append("<div class='tool-params-label'>Parameters:</div>")
            contentParts.append("<pre class='tool-params'><code>${escapeHtml(args)}</code></pre>")
        }
        contentParts.append("\u2705 Completed")
        return "<div class='collapse-section tool-section collapsed' id='$id'>" +
            "<div class='collapse-header' tabindex='0' role='button' aria-expanded='false' onclick='toggleTool(\"$id\")' onkeydown='if(event.key===\"Enter\"||event.key===\" \")this.click()'>" +
            "<span class='collapse-icon'>✓</span>" +
            "<span class='collapse-label'>${escapeHtml(displayName)}</span>" +
            "<span class='caret'>\u25B8</span></div>" +
            "<div class='collapse-content'>$contentParts</div></div>"
    }

    private fun renderBatchSubagent(
        obj: com.google.gson.JsonObject,
        childTools: List<com.google.gson.JsonObject> = emptyList()
    ): String {
        val agentType = obj["agentType"]?.asString ?: AGENT_TYPE_GENERAL
        val info = SUB_AGENT_INFO[agentType] ?: SubAgentInfo(
            agentType.replaceFirstChar { it.uppercase() } + " Agent")
        val safeName = escapeHtml(info.displayName)
        val prompt = obj["prompt"]?.asString?.ifEmpty { null }
        val result = obj["result"]?.asString?.ifEmpty { null }
        val status = obj["status"]?.asString?.ifEmpty { null }
        val colorIndex = obj["colorIndex"]?.asInt ?: (nextSubAgentColor++ % SA_COLOR_COUNT)
        val cssClass = "subagent-c$colorIndex"
        val wrapperId = "sa-batch-${entryCounter}"
        val sb = StringBuilder("<div class='$cssClass' id='$wrapperId'>")
        if (!prompt.isNullOrBlank()) {
            sb.append(AGENT_ROW_OPEN)
            sb.append("<span class='subagent-prefix'>@$safeName</span>")
            sb.append(" ${escapeHtml(prompt)}")
            sb.append(DIV_CLOSE_2)
        }
        // Render child tool entries inside the wrapper
        for (toolObj in childTools) {
            sb.append(renderBatchTool(toolObj))
        }
        sb.append("<div class='agent-row'><div class='meta' id='meta-$wrapperId'></div>")
        sb.append("<div class='subagent-bubble' onclick='toggleMeta(this)'>")
        when {
            !result.isNullOrBlank() -> sb.append(markdownToHtml(result))
            status == "failed" -> sb.append("<span style='color:var(--error)'>\u2716 Failed</span>")
            else -> sb.append("Completed")
        }
        sb.append("</div></div></div>")
        return sb.toString()
    }

    private fun renderBatchStatus(obj: com.google.gson.JsonObject): String {
        val icon = obj["icon"]?.asString ?: ""
        val msg = obj["message"]?.asString ?: ""
        return if (icon == ICON_ERROR) {
            "<div class='status-row error'>$ICON_ERROR ${escapeHtml(msg)}</div>"
        } else {
            "<div class='status-row info'>\u2139\uFE0F ${escapeHtml(msg)}</div>"
        }
    }

    private fun renderBatchSeparator(obj: com.google.gson.JsonObject): String {
        val ts = obj["timestamp"]?.asString ?: ""
        return "<div class='session-sep'><span class='session-sep-line'></span>" +
            "<span class='session-sep-label'>New session \u00B7 ${escapeHtml(ts)}</span>" +
            "<span class='session-sep-line'></span></div>"
    }

    private fun renderBatchHtml(batch: List<com.google.gson.JsonElement>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < batch.size) {
            val obj = batch[i].asJsonObject
            when (obj["type"]?.asString) {
                "prompt" -> sb.append(renderBatchPrompt(obj))
                "text" -> sb.append(renderBatchText(obj))
                "thinking" -> sb.append(renderBatchThinking(obj))
                "tool" -> sb.append(renderBatchTool(obj))
                "subagent" -> {
                    // Collect following tool entries that belong to this sub-agent
                    val childTools = mutableListOf<com.google.gson.JsonObject>()
                    while (i + 1 < batch.size && batch[i + 1].asJsonObject["type"]?.asString == "tool") {
                        i++
                        childTools.add(batch[i].asJsonObject)
                    }
                    sb.append(renderBatchSubagent(obj, childTools))
                }

                "status" -> sb.append(renderBatchStatus(obj))
                "separator" -> sb.append(renderBatchSeparator(obj))
            }
            i++
        }
        return sb.toString()
    }

    private val exporter: ConversationExporter get() = ConversationExporter(entries)

    fun getConversationText(): String = exporter.getConversationText()

    /**
     * Produce a compressed summary of the conversation for context injection.
     * Omits thinking blocks, truncates long responses, and caps total size.
     */
    fun getCompressedSummary(maxChars: Int = 8000): String = exporter.getCompressedSummary(maxChars)

    /** Returns the conversation as a self-contained HTML document */
    fun getConversationHtml(): String = exporter.getConversationHtml()

    fun finishResponse(toolCallCount: Int = 0, modelId: String = "", multiplier: String = "1x") {
        finalizeCurrentText()
        collapseThinking()
        activeSubAgentWrapperId = null
        if (pendingAgentMetaId != null) {
            // Tool calls happened but no text followed — remove the "Working…" placeholder
            val id = "text-$entryCounter"
            executeJs("(function(){var e=document.getElementById('$id');if(e){var p=e.querySelector('.agent-pending');if(p)p.remove();}})()")
            pendingAgentMetaId = null
        }
        val statsJson = """{"tools":$toolCallCount,"model":"${escapeJs(modelId)}","mult":"${escapeJs(multiplier)}"}"""
        executeJs("finalizeTurn($statsJson)")
        trimMessages()
        // Force repaint to clear any rendering artifacts from streaming
        SwingUtilities.invokeLater { browser?.component?.repaint() }
    }

    fun showQuickReplies(options: List<String>) {
        if (options.isEmpty()) return
        val json = options.joinToString(",") { "\"${escapeJs(it)}\"" }
        executeJs("showQuickReplies([$json])")
    }

    fun disableQuickReplies() {
        executeJs("disableQuickReplies()")
    }

    /** Returns the raw text of the most recent agent response entry. */
    fun getLastResponseText(): String {
        return entries.filterIsInstance<EntryData.Text>().lastOrNull()?.raw?.toString() ?: ""
    }

    private fun trimMessages() {
        // Keep at most 100 top-level rows to prevent performance degradation,
        // then remove any session separators that no longer have content after them
        executeJs(
            """(function(){
            var c=document.getElementById('container');if(!c)return;
            var rows=c.querySelectorAll('.prompt-row,.agent-row,.thinking-section,.tool-section,.context-section,.status-row');
            var limit=100;
            if(rows.length>limit){
                for(var i=0;i<rows.length-limit;i++){rows[i].remove();}
            }
            var seps=c.querySelectorAll('.session-sep');
            for(var j=0;j<seps.length;j++){
                var n=seps[j].nextElementSibling;var ok=false;
                while(n&&!n.classList.contains('session-sep')){
                    if(n.classList.contains('prompt-row')||n.classList.contains('agent-row')||
                       n.classList.contains('status-row')){ok=true;break;}
                    n=n.nextElementSibling;
                }
                if(!ok)seps[j].remove();
            }
        })()"""
        )
    }

    override fun dispose() { /* children auto-disposed via Disposer */
    }

// --- Internal ---

    private fun finalizeCurrentText() {
        val data = currentTextData ?: return
        currentTextData = null
        val rawText = data.raw.toString()
        val id = "text-$entryCounter"
        if (rawText.isBlank()) {
            executeJs("(function(){var e=document.getElementById('$id');if(e)e.parentElement.remove();})()")
            entries.remove(data); return
        }
        // Strip [quick-reply: ...] tags from rendered output (buttons rendered separately)
        val cleanText = rawText.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = markdownToHtml(cleanText)
            val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
            SwingUtilities.invokeLater {
                executeJs("(function(){var e=document.getElementById('$id');if(e){e.innerHTML=b64('$encoded');e.classList.remove('streaming-bubble');scrollIfNeeded();}})()")
            }
        }
    }

    private fun appendHtml(html: String) {
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        executeJs(
            """(function(){var c=document.getElementById('container');
            c.insertAdjacentHTML('beforeend',b64('$encoded'));
            scrollIfNeeded();})()"""
        )
    }

    /** Insert HTML before the pending "Working…" agent row so expanded sections appear above the bubble. */
    private fun insertHtmlBeforePendingBubble(html: String) {
        val metaId = pendingAgentMetaId
        if (metaId == null) {
            appendHtml(html)
            return
        }
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        executeJs(
            """(function(){var m=document.getElementById('$metaId');if(!m){return;}
            var row=m.parentElement;var c=row.parentElement;
            var tmp=document.createElement('div');tmp.innerHTML=b64('$encoded');
            while(tmp.firstChild)c.insertBefore(tmp.firstChild,row);
            scrollIfNeeded();})()"""
        )
    }

    /** Insert HTML inside a sub-agent wrapper div, before its last child (the result row). */
    private fun appendHtmlToSubAgent(html: String, wrapperId: String) {
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        executeJs(
            """(function(){var w=document.getElementById('$wrapperId');if(!w)return;
            var last=w.lastElementChild;
            var tmp=document.createElement('div');tmp.innerHTML=b64('$encoded');
            while(tmp.firstChild)w.insertBefore(tmp.firstChild,last);
            scrollIfNeeded();})()"""
        )
    }

    private fun executeJs(js: String) {
        if (browser == null) return
        if (browserReady) browser.cefBrowser.executeJavaScript(js, "", 0)
        else pendingJs.add(js)
    }

    private fun handleFileLink(desc: String) {
        if (!desc.startsWith("openfile://")) return
        val pathAndLine = desc.removePrefix("openfile://")
        val parts = pathAndLine.split(":")
        val filePath = parts[0]
        val line = parts.getOrNull(1)?.toIntOrNull()
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater
            OpenFileDescriptor(project, vf, maxOf(0, (line ?: 1) - 1), 0).navigate(true)
        }
    }

// --- Initial HTML page ---

    /** Load a classpath resource as a UTF-8 string */
    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Missing resource: $path")

    private fun buildCssVars(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val bg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
        val codeBg = UIManager.getColor("Editor.backgroundColor")
            ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder = UIManager.getColor("TableCell.borderColor")
            ?: JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg = UIManager.getColor("TableHeader.background")
            ?: JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val spinBg = UIManager.getColor("Panel.background")
            ?: JBColor(Color(0xDD, 0xDD, 0xDD), Color(0x55, 0x55, 0x55))
        val linkColor = UIManager.getColor(LINK_COLOR_KEY)
            ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))
        val tooltipBg = UIManager.getColor("ToolTip.background")
            ?: JBColor(Color(0xF7, 0xF7, 0xF7), Color(0x3C, 0x3F, 0x41))
        return """
            --font-family: '${font.family}';
            --font-size: ${font.size - 2}pt;
            --code-font-size: ${font.size - 3}pt;
            --fg: ${rgb(fg)};
            --fg-a08: ${rgba(fg, 0.08)};
            --fg-a16: ${rgba(fg, 0.16)};
            --bg: ${rgb(bg)};
            --user: ${rgb(USER_COLOR)};
            --user-a06: ${rgba(USER_COLOR, 0.06)};
            --user-a08: ${rgba(USER_COLOR, 0.08)};
            --user-a12: ${rgba(USER_COLOR, 0.12)};
            --user-a15: ${rgba(USER_COLOR, 0.15)};
            --user-a16: ${rgba(USER_COLOR, 0.16)};
            --user-a18: ${rgba(USER_COLOR, 0.18)};
            --user-a25: ${rgba(USER_COLOR, 0.25)};
            --agent: ${rgb(AGENT_COLOR)};
            --agent-a06: ${rgba(AGENT_COLOR, 0.06)};
            --agent-a08: ${rgba(AGENT_COLOR, 0.08)};
            --agent-a10: ${rgba(AGENT_COLOR, 0.10)};
            --agent-a16: ${rgba(AGENT_COLOR, 0.16)};
            --think: ${rgb(THINK_COLOR)};
            --think-a04: ${rgba(THINK_COLOR, 0.04)};
            --think-a06: ${rgba(THINK_COLOR, 0.06)};
            --think-a08: ${rgba(THINK_COLOR, 0.08)};
            --think-a10: ${rgba(THINK_COLOR, 0.10)};
            --think-a16: ${rgba(THINK_COLOR, 0.16)};
            --think-a25: ${rgba(THINK_COLOR, 0.25)};
            --think-a30: ${rgba(THINK_COLOR, 0.30)};
            --think-a35: ${rgba(THINK_COLOR, 0.35)};
            --think-a40: ${rgba(THINK_COLOR, 0.40)};
            --think-a55: ${rgba(THINK_COLOR, 0.55)};
            --tool: ${rgb(TOOL_COLOR)};
            --tool-a08: ${rgba(TOOL_COLOR, 0.08)};
            --tool-a16: ${rgba(TOOL_COLOR, 0.16)};
            --tool-a40: ${rgba(TOOL_COLOR, 0.40)};
            --spin-bg: ${rgb(spinBg)};
            --code-bg: ${rgb(codeBg)};
            --tbl-border: ${rgb(tblBorder)};
            --th-bg: ${rgb(thBg)};
            --link: ${rgb(linkColor)};
            --tooltip-bg: ${rgb(tooltipBg)};
            --error: ${rgb(ERROR_COLOR)};
            --error-a05: ${rgba(ERROR_COLOR, 0.05)};
            --error-a06: ${rgba(ERROR_COLOR, 0.06)};
            --error-a12: ${rgba(ERROR_COLOR, 0.12)};
            --error-a16: ${rgba(ERROR_COLOR, 0.16)};
            --shadow: ${rgba(THINK_COLOR, 0.25)};
            --sa-explore: ${rgb(SA_EXPLORE_COLOR)};
            --sa-explore-a06: ${rgba(SA_EXPLORE_COLOR, 0.06)};
            --sa-explore-a10: ${rgba(SA_EXPLORE_COLOR, 0.10)};
            --sa-explore-a15: ${rgba(SA_EXPLORE_COLOR, 0.15)};
            --sa-task: ${rgb(SA_TASK_COLOR)};
            --sa-task-a06: ${rgba(SA_TASK_COLOR, 0.06)};
            --sa-task-a10: ${rgba(SA_TASK_COLOR, 0.10)};
            --sa-task-a15: ${rgba(SA_TASK_COLOR, 0.15)};
            --sa-general: ${rgb(SA_GENERAL_COLOR)};
            --sa-general-a06: ${rgba(SA_GENERAL_COLOR, 0.06)};
            --sa-general-a10: ${rgba(SA_GENERAL_COLOR, 0.10)};
            --sa-general-a15: ${rgba(SA_GENERAL_COLOR, 0.15)};
            --sa-review: ${rgb(SA_REVIEW_COLOR)};
            --sa-review-a06: ${rgba(SA_REVIEW_COLOR, 0.06)};
            --sa-review-a10: ${rgba(SA_REVIEW_COLOR, 0.10)};
            --sa-review-a15: ${rgba(SA_REVIEW_COLOR, 0.15)};
            --sa-ui: ${rgb(SA_UI_COLOR)};
            --sa-ui-a06: ${rgba(SA_UI_COLOR, 0.06)};
            --sa-ui-a10: ${rgba(SA_UI_COLOR, 0.10)};
            --sa-ui-a15: ${rgba(SA_UI_COLOR, 0.15)};
        """.trimIndent()
    }

    /** Re-inject CSS custom properties when the IDE theme changes */
    private fun updateThemeColors() {
        val vars = buildCssVars().replace("'", "\\'").replace("\n", " ")
        executeJs("document.documentElement.style.cssText='$vars'")
        // Also update the JCEF initial background for next load
        val panelBg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    private fun buildInitialPage(): String {
        val cssVars = buildCssVars()

        // JS bridge: wire JCEF query callbacks to window._bridge methods
        val fileHandler = openFileQuery!!.inject("href")
        val bridgeJs = """
            window._bridge = {
                openFile: function(href) { $fileHandler },
                openUrl: function(url) { $openUrlBridgeJs },
                setCursor: function(c) { $cursorBridgeJs },
                loadMore: function() { $loadMoreBridgeJs },
                quickReply: function(text) { $quickReplyBridgeJs }
            };
        """.trimIndent()

        val css = loadResource("/chat-console/chat-console.css")
        val js = loadResource("/chat-console/chat-console.js")

        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<style>$css</style>
<style>:root { $cssVars }</style></head><body>
<div id="container"></div>
<script>$bridgeJs</script>
<script>$js</script></body></html>"""
    }

    private fun markdownToHtml(text: String): String =
        MarkdownRenderer.markdownToHtml(text, ::resolveFileReference, ::resolveFilePath)

// --- Tool content file ---

    /** Write markdown content from a tool's arguments to a file, return the absolute path or null. */
    private fun writeToolContentFile(toolName: String, contentKey: String, arguments: String): String? {
        return try {
            val json = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            val content = json[contentKey]?.asString ?: return null
            val base = project.basePath ?: return null
            val dir = File(base, ".agent-work")
            if (!dir.exists()) dir.mkdirs()
            val baseName = toolName.substringAfterLast("-")
            val fileName = when (baseName) {
                "update_todo" -> "agent-todo.md"
                else -> "$baseName.md"
            }
            val file = File(dir, fileName)
            file.writeText(content)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

// --- File resolution ---

    private fun resolveFileReference(ref: String): Pair<String, Int?>? {
        val colonIdx = ref.indexOf(':')
        val (name, lineNum) = if (colonIdx > 0) {
            val afterColon = ref.substring(colonIdx + 1)
            val num = afterColon.split(",", " ").firstOrNull()?.toIntOrNull()
            if (num != null) ref.substring(0, colonIdx) to num else ref to null
        } else ref to null

        val path = resolveFilePath(name)
            ?: if (!name.contains("/") && name.contains(".")) findProjectFileByName(name) else null
        return if (path != null) Pair(path, lineNum) else null
    }

    private fun resolveFilePath(path: String): String? {
        val f = File(path)
        if (f.isAbsolute) return if (f.exists()) f.absolutePath else null
        val base = project.basePath ?: return null
        val rel = File(base, path)
        return if (rel.exists()) rel.absolutePath else null
    }

    private fun findProjectFileByName(name: String): String? = try {
        var result: String? = null
        ReadAction.run<Throwable> {
            val files: Collection<com.intellij.openapi.vfs.VirtualFile> =
                FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            if (files.size == 1) result = files.first().path
        }
        result
    } catch (_: Exception) {
        null
    }
}
