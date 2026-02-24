package com.github.copilot.intellij.ui

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
        private val USER_COLOR = JBColor(Color(0x29, 0x79, 0xFF), Color(0x5C, 0x9D, 0xFF))
        private val AGENT_COLOR = JBColor(Color(0x43, 0xA0, 0x47), Color(0x66, 0xBB, 0x6A))
        private val TOOL_COLOR = JBColor(Color(0xFF, 0xA7, 0x26), Color(0xFF, 0xB7, 0x4D))
        private val THINK_COLOR = JBColor(Color(0x99, 0x99, 0x99), Color(0x88, 0x88, 0x88))

        private val FILE_PATH_REGEX = Regex(
            """(?<![:\w])(?:/[\w.\-]+(?:/[\w.\-]+)*\.\w+|(?:\.\.?/)?[\w.\-]+(?:/[\w.\-]+)+\.\w+)(?::\d+(?::\d+)?)?"""
        )

        /** Human-readable name and short description for each tool */
        private data class ToolInfo(val displayName: String, val description: String)

        private val TOOL_DISPLAY_INFO = mapOf(
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
            return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }

        private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
        private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"
    }

    // --- Data model ---
    private sealed class EntryData {
        class Prompt(val text: String) : EntryData()
        class Text(val raw: StringBuilder = StringBuilder()) : EntryData()
        class Thinking(val raw: StringBuilder = StringBuilder()) : EntryData()
        class ToolCall(val title: String, val arguments: String? = null) : EntryData()
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

    // JCEF
    private val browser: JBCefBrowser?
    private val openFileQuery: JBCefJSQuery?
    private var browserReady = false
    private val pendingJs = mutableListOf<String>()
    private var cursorBridgeJs = ""

    // Swing fallback
    private val fallbackArea: JBTextArea?

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            openFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openFileQuery.addHandler { handleFileLink(it); null }
            Disposer.register(this, openFileQuery)
            Disposer.register(this, browser)

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
                "<a class='prompt-ctx-chip' href='$href' title='${escapeHtml(path)}${if (line > 0) ":$line" else ""}'>📎 ${
                    escapeHtml(
                        name
                    )
                }</a>"
            }
        } else ""
        val html =
            "<div class='prompt-row'><div class='meta'><span class='ts'>$ts</span>$ctxChips</div><div class='prompt-bubble' onclick='toggleMeta(this)'>${
                escapeHtml(text)
            }</div></div>"
        appendHtml(html)
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
        hideProcessingIndicator()
        if (currentThinkingData == null) {
            currentThinkingData = EntryData.Thinking().also { entries.add(it) }
            thinkingCounter++
            val html = """<div class='collapse-section thinking-section' id='think-$thinkingCounter'>
                <div class='collapse-header' onclick='toggleThinking("think-$thinkingCounter")'>
                    <span class='collapse-icon thinking-pulse'>💭</span>
                    <span class='collapse-label'>Thinking...</span>
                    <span class='caret'>▾</span>
                </div>
                <div class='collapse-content'></div>
            </div>"""
            appendHtml(html)
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
            el.querySelector('.caret').textContent='▸';})()"""
        )
    }

    fun appendText(text: String) {
        collapseThinking()
        hideProcessingIndicator()
        if (currentTextData == null) {
            currentTextData = EntryData.Text().also { entries.add(it) }
            entryCounter++
            val ts = timestamp()
            val html =
                "<div class='agent-row'><div class='meta' id='meta-$entryCounter'><span class='ts'>$ts</span></div><div class='agent-bubble' id='text-$entryCounter' onclick='toggleMeta(this)'><pre class='streaming'></pre></div></div>"
            appendHtml(html)
        }
        currentTextData!!.raw.append(text)
        val id = "text-$entryCounter"
        executeJs("(function(){var e=document.querySelector('#$id .streaming');if(e){e.textContent+='${escapeJs(text)}';scrollIfNeeded();}})()")
        fallbackArea?.let { SwingUtilities.invokeLater { it.append(text) } }
    }

    fun addToolCallEntry(id: String, title: String, arguments: String? = null) {
        finalizeCurrentText()
        hideProcessingIndicator()
        entries.add(EntryData.ToolCall(title, arguments))
        val did = domId(id)
        val baseName = title.substringAfterLast("-")
        val info = TOOL_DISPLAY_INFO[title] ?: TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replace("_", " ").replaceFirstChar { it.uppercase() }
        val safeDisplayName = escapeHtml(displayName)

        val contentParts = StringBuilder()
        if (info?.description != null) {
            contentParts.append("<div class='tool-desc'>${escapeHtml(info.description)}</div>")
        }
        if (!arguments.isNullOrBlank()) {
            contentParts.append(
                "<div class='tool-params-label'>Parameters:</div><pre class='tool-params'><code>${
                    escapeHtml(
                        arguments
                    )
                }</code></pre>"
            )
        }
        contentParts.append("<div class='tool-result' id='result-$did'><span class='tool-result-pending'>Running...</span></div>")

        val html = """<div class='collapse-section tool-section collapsed' id='tool-$did'>
            <div class='collapse-header' onclick='toggleTool("tool-$did")'>
                <span class='collapse-icon'><span class='tool-spinner'></span></span>
                <span class='collapse-label'>$safeDisplayName</span>
                <span class='caret'>▸</span>
            </div>
            <div class='collapse-content'>$contentParts</div></div>"""
        appendHtml(html)
        fallbackArea?.let { SwingUtilities.invokeLater { it.append("⚒ $displayName\n") } }
    }

    fun updateToolCall(id: String, status: String, details: String? = null) {
        val did = domId(id)
        val doneColor = rgb(JBColor(Color(0x59, 0x8C, 0x4D), Color(0x6A, 0x9F, 0x59)))
        val resultContent = if (!details.isNullOrBlank()) {
            "<div class='tool-result-label'>Output:</div><pre class='tool-output'><code>${escapeHtml(details)}</code></pre>"
        } else {
            if (status == "completed") "✓ Completed" else "✖ Failed"
        }
        val encoded = Base64.getEncoder().encodeToString(resultContent.toByteArray(Charsets.UTF_8))
        when (status) {
            "completed" -> executeJs(
                """(function(){var el=document.getElementById('tool-$did');if(!el)return;
                var icon=el.querySelector('.collapse-icon');icon.innerHTML='✓';icon.style.color='$doneColor';
                el.querySelector('.collapse-label').style.color='$doneColor';
                var r=document.getElementById('result-$did');if(r)r.innerHTML=b64('$encoded');
                collapseToolToChip('tool-$did');})()"""
            )

            "failed" -> executeJs(
                """(function(){var el=document.getElementById('tool-$did');if(!el)return;
                var icon=el.querySelector('.collapse-icon');icon.innerHTML='✖';icon.style.color='red';
                el.querySelector('.collapse-label').style.color='red';
                var r=document.getElementById('result-$did');if(r)r.innerHTML=b64('$encoded');
                collapseToolToChip('tool-$did');})()"""
            )
        }
    }

    fun addErrorEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status("✖", message))
        appendHtml("<div class='status-row error'>✖ ${escapeHtml(message)}</div>")
    }

    fun addInfoEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status("ℹ", message))
        appendHtml("<div class='status-row info'>ℹ ${escapeHtml(message)}</div>")
    }

    /** Show a bouncing-dots indicator at the bottom of the chat while the agent is processing. */
    fun showProcessingIndicator() {
        executeJs(
            """(function(){
            if(document.getElementById('processing-ind'))return;
            var d=document.createElement('div');d.id='processing-ind';d.className='processing-indicator';
            d.innerHTML='<div class="processing-dot"></div><div class="processing-dot"></div><div class="processing-dot"></div>';
            document.getElementById('container').appendChild(d);scrollIfNeeded();
        })()"""
        )
    }

    /** Remove the bouncing-dots processing indicator. */
    fun hideProcessingIndicator() {
        executeJs("(function(){var e=document.getElementById('processing-ind');if(e)e.remove();})()")
    }

    fun hasContent(): Boolean = entries.isNotEmpty()

    /** Adds a visual separator marking previous session content as stale */
    fun addSessionSeparator(timestamp: String) {
        finalizeCurrentText()
        entries.add(EntryData.SessionSeparator(timestamp))
        val html =
            "<div class='session-sep'><span class='session-sep-line'></span><span class='session-sep-label'>New session · ${
                escapeHtml(timestamp)
            }</span><span class='session-sep-line'></span></div>"
        appendHtml(html)
    }

    fun showPlaceholder(text: String) {
        entries.clear(); currentTextData = null; currentThinkingData = null; entryCounter = 0; thinkingCounter =
            0; contextCounter = 0
        executeJs("document.getElementById('container').innerHTML='<div class=\"placeholder\">${escapeJs(escapeHtml(text))}</div>'")
        fallbackArea?.let { SwingUtilities.invokeLater { it.text = text } }
    }

    fun clear() {
        entries.clear(); currentTextData = null; currentThinkingData = null; entryCounter = 0; thinkingCounter =
            0; contextCounter = 0
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
            for (elem in arr) {
                val obj = elem.asJsonObject
                when (obj["type"]?.asString) {
                    "prompt" -> addPromptEntry(obj["text"]?.asString ?: "")
                    "text" -> {
                        appendText(obj["raw"]?.asString ?: ""); finalizeCurrentText()
                    }

                    "thinking" -> {
                        appendThinkingText(obj["raw"]?.asString ?: ""); collapseThinking()
                    }

                    "tool" -> addToolCallEntry(
                        "restored-${entryCounter}", obj["title"]?.asString ?: "",
                        obj["args"]?.asString?.ifEmpty { null }
                    )

                    "context" -> {
                        val files = obj["files"]?.asJsonArray?.map {
                            val f = it.asJsonObject; Pair(f["name"]?.asString ?: "", f["path"]?.asString ?: "")
                        } ?: emptyList()
                        addContextFilesEntry(files)
                    }

                    "status" -> {
                        val icon = obj["icon"]?.asString ?: ""
                        val msg = obj["message"]?.asString ?: ""
                        if (icon == "✖") addErrorEntry(msg) else addInfoEntry(msg)
                    }

                    "separator" -> addSessionSeparator(obj["timestamp"]?.asString ?: "")
                }
            }
            // Collapse all restored entries into chips
            executeJs("finalizeTurn({})")
        } catch (_: Exception) { /* best-effort restore */
        }
    }

    fun getConversationText(): String {
        val sb = StringBuilder()
        for (e in entries) when (e) {
            is EntryData.Prompt -> sb.appendLine(">>> ${e.text}")
            is EntryData.Text -> {
                sb.append(e.raw); sb.appendLine()
            }

            is EntryData.Thinking -> sb.appendLine("[thinking] ${e.raw}")
            is EntryData.ToolCall -> {
                val baseName = e.title.substringAfterLast("-")
                val info = TOOL_DISPLAY_INFO[e.title] ?: TOOL_DISPLAY_INFO[baseName]
                val name = info?.displayName ?: e.title
                sb.appendLine("⚒ $name")
                if (e.arguments != null) sb.appendLine("  params: ${e.arguments}")
            }

            is EntryData.ContextFiles -> sb.appendLine("📎 ${e.files.size} context file(s): ${e.files.joinToString(", ") { it.first }}")
            is EntryData.Status -> sb.appendLine("${e.icon} ${e.message}")
            is EntryData.SessionSeparator -> sb.appendLine("--- Previous session — ${e.timestamp} ---")
        }
        return sb.toString()
    }

    /**
     * Produce a compressed summary of the conversation for context injection.
     * Omits thinking blocks, truncates long responses, and caps total size.
     */
    fun getCompressedSummary(maxChars: Int = 8000): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[Previous conversation summary]")
        for (e in entries) when (e) {
            is EntryData.Prompt -> sb.appendLine("User: ${e.text}")
            is EntryData.Text -> {
                val raw = e.raw.toString().trim()
                if (raw.isNotEmpty()) {
                    val truncated = if (raw.length > 600) raw.take(600) + "...[truncated]" else raw
                    sb.appendLine("Agent: $truncated")
                }
            }

            is EntryData.ToolCall -> {
                val baseName = e.title.substringAfterLast("-")
                val info = TOOL_DISPLAY_INFO[e.title] ?: TOOL_DISPLAY_INFO[baseName]
                val name = info?.displayName ?: e.title
                sb.appendLine("Tool: $name")
            }

            is EntryData.ContextFiles -> sb.appendLine("Context: ${e.files.joinToString(", ") { it.first }}")
            is EntryData.SessionSeparator -> sb.appendLine("--- ${e.timestamp} ---")
            is EntryData.Thinking -> {}
            is EntryData.Status -> {}
        }
        val result = sb.toString()
        if (result.length <= maxChars) return result
        return "[Previous conversation summary - trimmed to recent]\n..." +
            result.substring(result.length - maxChars + 60)
    }

    /** Returns the conversation as a self-contained HTML document */
    fun getConversationHtml(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val codeBg = JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg = JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val linkColor = UIManager.getColor("Component.linkColor")
            ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))

        val sb = StringBuilder()
        sb.append(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:'${font.family}',system-ui,sans-serif;font-size:${font.size - 2}pt;color:${rgb(fg)};line-height:1.45;max-width:900px;margin:0 auto;padding:16px}
.prompt{text-align:right;margin:10px 0 4px 0}
.prompt-b{display:inline-block;background:${
                rgba(
                    USER_COLOR,
                    0.12
                )
            };border-radius:16px 16px 4px 16px;padding:6px 14px;max-width:85%;text-align:left;font-size:0.92em}
.response{background:${
                rgba(
                    AGENT_COLOR,
                    0.06
                )
            };border-radius:4px 16px 16px 16px;padding:8px 16px;margin:4px 0;max-width:95%}
.thinking{background:${
                rgba(
                    THINK_COLOR,
                    0.06
                )
            };border-radius:8px;padding:6px 12px;margin:4px 0;font-size:0.88em;color:${rgb(THINK_COLOR)}}
.tool{display:inline-flex;align-items:center;gap:6px;background:${rgba(TOOL_COLOR, 0.1)};border:1px solid ${
                rgba(
                    TOOL_COLOR,
                    0.3
                )
            };border-radius:20px;padding:3px 12px;margin:2px 0;font-size:0.88em;color:${rgb(TOOL_COLOR)}}
.context{font-size:0.88em;color:${rgb(USER_COLOR)};margin:2px 0}
.context summary{cursor:pointer;padding:4px 0}
.context .ctx-file{padding:2px 0;padding-left:8px}
.context .ctx-file a{color:${rgb(linkColor)};text-decoration:none}
.status{padding:4px 8px;margin:2px 0;font-size:0.88em}
.status.error{color:red} .status.info{color:${rgb(THINK_COLOR)}}
code{background:${rgb(codeBg)};padding:2px 5px;border-radius:4px;font-family:'JetBrains Mono',monospace;font-size:${font.size - 3}pt}
pre{background:${rgb(codeBg)};padding:10px;border-radius:6px;margin:6px 0;overflow-x:auto}
pre code{background:none;padding:0;border-radius:0;display:block}
table{border-collapse:collapse;margin:6px 0}
th,td{border:1px solid ${rgb(tblBorder)};padding:4px 10px;text-align:left}
th{background:${rgb(thBg)};font-weight:600}
a{color:${rgb(linkColor)}}
ul,ol{margin:4px 0;padding-left:22px}
</style></head><body>
"""
        )
        for (e in entries) when (e) {
            is EntryData.Prompt -> sb.append("<div class='prompt'><span class='prompt-b'>${escapeHtml(e.text)}</span></div>\n")
            is EntryData.Text -> sb.append("<div class='response'>").append(markdownToHtml(e.raw.toString()))
                .append("</div>\n")

            is EntryData.Thinking -> sb.append(
                "<details class='thinking'><summary>💭 Thought process</summary><pre>${escapeHtml(e.raw.toString())}</pre></details>\n"
            )

            is EntryData.ToolCall -> {
                val baseName = e.title.substringAfterLast("-")
                val info = TOOL_DISPLAY_INFO[e.title] ?: TOOL_DISPLAY_INFO[baseName]
                val displayName = info?.displayName ?: e.title
                sb.append("<details class='tool'><summary>⚒ ${escapeHtml(displayName)}</summary>")
                if (info?.description != null) sb.append("<div style='font-style:italic;margin:4px 0'>${escapeHtml(info.description)}</div>")
                if (e.arguments != null) sb.append(
                    "<div style='margin:4px 0'><b>Parameters:</b><pre><code>${
                        escapeHtml(
                            e.arguments
                        )
                    }</code></pre></div>"
                )
                sb.append("</details>\n")
            }

            is EntryData.ContextFiles -> {
                val label = "${e.files.size} context file${if (e.files.size != 1) "s" else ""} attached"
                sb.append("<details class='context'><summary>📎 $label</summary>")
                e.files.forEach { (name, _) -> sb.append("<div class='ctx-file'><code>${escapeHtml(name)}</code></div>") }
                sb.append("</details>\n")
            }

            is EntryData.Status -> sb.append(
                "<div class='status ${if (e.icon == "✖") "error" else "info"}'>${e.icon} ${escapeHtml(e.message)}</div>\n"
            )

            is EntryData.SessionSeparator -> sb.append(
                "<hr style='border:none;border-top:1px solid #555;margin:16px 0'><div style='text-align:center;font-size:0.85em;color:#888'>Previous session — ${
                    escapeHtml(
                        e.timestamp
                    )
                }</div>\n"
            )
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    fun finishResponse(toolCallCount: Int = 0, modelId: String = "", multiplier: String = "1x") {
        finalizeCurrentText()
        collapseThinking()
        hideProcessingIndicator()
        val statsJson = """{"tools":$toolCallCount,"model":"${escapeJs(modelId)}","mult":"${escapeJs(multiplier)}"}"""
        executeJs("finalizeTurn($statsJson)")
        trimMessages()
    }

    private fun trimMessages() {
        // Keep at most 100 top-level rows to prevent performance degradation
        executeJs(
            """(function(){
            var c=document.getElementById('container');if(!c)return;
            var rows=c.querySelectorAll('.prompt-row,.agent-row,.thinking-section,.tool-section,.context-section,.status-row');
            var limit=100;
            if(rows.length>limit){
                for(var i=0;i<rows.length-limit;i++){rows[i].remove();}
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = markdownToHtml(rawText)
            val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
            SwingUtilities.invokeLater {
                executeJs("(function(){var e=document.getElementById('$id');if(e){e.innerHTML=b64('$encoded');e.classList.remove('streaming-bubble');scrollIfNeeded();}})()")
            }
        }
    }

    private fun appendHtml(html: String) {
        val encoded = Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
        executeJs("(function(){document.getElementById('container').insertAdjacentHTML('beforeend',b64('$encoded'));scrollIfNeeded();})()")
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

    private fun buildInitialPage(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val bg = UIUtil.getPanelBackground()
        val codeBg = JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg = JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val spinBg = JBColor(Color(0xDD, 0xDD, 0xDD), Color(0x55, 0x55, 0x55))
        val linkColor = UIManager.getColor("Component.linkColor")
            ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))
        val fileHandler = openFileQuery!!.inject("el.getAttribute('href')")

        return """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'${font.family}',system-ui,sans-serif;font-size:${font.size - 2}pt;
     color:${rgb(fg)};background:${rgb(bg)};padding:8px;line-height:1.45}

/* --- Scrollbar (IntelliJ-style thin track) --- */
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:${rgba(THINK_COLOR, 0.35)};border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:${rgba(THINK_COLOR, 0.55)}}

/* --- User prompt bubble (right-aligned) --- */
.prompt-row{display:flex;flex-direction:column;align-items:flex-end;margin:10px 0 4px 0}
.prompt-bubble{background:${rgba(USER_COLOR, 0.12)};border-radius:16px 16px 4px 16px;
    padding:6px 14px;max-width:85%;font-weight:normal;white-space:pre-wrap;font-size:0.88em}
.prompt-bubble:hover{background:${rgba(USER_COLOR, 0.18)}}
.prompt-ctx-chip{display:inline-flex;align-items:center;gap:2px;background:${rgba(USER_COLOR, 0.15)};
    border-radius:10px;padding:1px 8px;font-size:0.82em;color:${rgb(USER_COLOR)};text-decoration:none;white-space:nowrap}
.prompt-ctx-chip:hover{text-decoration:none;background:${rgba(USER_COLOR, 0.25)}}

/* --- Agent response bubble (left-aligned) --- */
.agent-row{margin:4px 0}
.agent-bubble{background:${rgba(AGENT_COLOR, 0.06)};border-radius:4px 16px 16px 16px;
    padding:8px 16px;max-width:95%}
.agent-bubble:hover{background:${rgba(AGENT_COLOR, 0.10)}}
.agent-bubble .streaming{white-space:pre-wrap;margin:0;background:none;padding:0;
    font-family:inherit;font-size:inherit}

/* --- Collapsible section (shared by thinking + tool calls) --- */
.collapse-section{margin:4px 0}
.collapse-header{cursor:pointer;display:flex;align-items:center;gap:6px;
    border-radius:8px;padding:5px 12px;font-size:0.88em;user-select:none;
    transition:background .15s}
.collapse-header:hover{filter:brightness(1.15)}
.collapse-content{white-space:pre-wrap;font-size:0.85em;padding:6px 12px 6px 30px;line-height:1.4}
.collapse-section.collapsed .collapse-content{display:none}
.collapse-icon{font-size:1em}
.collapse-label{flex:1}
.caret{font-size:0.8em;width:10px;text-align:center;color:${rgb(THINK_COLOR)}}

/* --- Thinking --- */
.thinking-section .collapse-header{background:${rgba(THINK_COLOR, 0.06)};color:${rgb(THINK_COLOR)}}
.thinking-section .collapse-content{color:${rgb(THINK_COLOR)}}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:0.4}}
.thinking-pulse{animation:pulse 1.5s ease-in-out infinite}

/* --- Tool calls --- */
.tool-section .collapse-header{background:${rgba(TOOL_COLOR, 0.08)};color:${rgb(TOOL_COLOR)}}
.tool-section .collapse-content{color:${rgb(THINK_COLOR)};white-space:normal}
.tool-spinner{display:inline-block;width:10px;height:10px;border:2px solid ${rgb(spinBg)};
    border-top-color:${rgb(TOOL_COLOR)};border-radius:50%;
    animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}

/* --- Context files --- */
.context-section .collapse-header{background:${rgba(USER_COLOR, 0.06)};color:${rgb(USER_COLOR)}}
.context-section .collapse-content{padding:4px 12px 4px 30px;white-space:normal}
.ctx-file{padding:2px 0}

/* --- Chip-expanded style (section opened from turn chip) --- */
.chip-expanded .collapse-header{display:none}
.chip-expanded .collapse-content{display:block;border-left:2px solid ${rgba(THINK_COLOR, 0.25)};
    margin:2px 0;padding:4px 10px;font-size:0.82em;position:relative}
.chip-expanded.tool-section .collapse-content{border-left-color:${rgba(TOOL_COLOR, 0.4)}}
.chip-expanded.thinking-section .collapse-content{border-left-color:${rgba(THINK_COLOR, 0.4)}}
.chip-close{position:absolute;top:2px;right:4px;cursor:pointer !important;font-size:0.8em;
    color:${rgb(THINK_COLOR)};opacity:0.6;padding:2px 4px;border-radius:4px}
.chip-close:hover{opacity:1;background:${rgba(THINK_COLOR, 0.1)}}
.ctx-file a{color:${rgb(linkColor)};text-decoration:none}
.ctx-file a:hover{text-decoration:underline}

/* --- Tool detail content --- */
.tool-desc{color:${rgb(THINK_COLOR)};font-style:italic;margin-bottom:4px}
.tool-params-label,.tool-result-label{color:${rgb(THINK_COLOR)};font-size:0.85em;margin:6px 0 2px 0;font-weight:600}
.tool-params{margin:2px 0 6px 0;padding:6px 8px;font-size:0.9em}
.tool-output{margin:2px 0;padding:6px 8px;font-size:0.9em}
.tool-result-pending{color:${rgb(THINK_COLOR)};font-style:italic}

/* --- Timestamps (hidden by default, shown on click) --- */
.meta{display:none;flex-wrap:wrap;align-items:center;gap:6px;margin-bottom:2px;width:100%}
.meta.show{display:flex}
.prompt-row .meta{justify-content:flex-end}
.ts{font-size:0.75em;color:${rgb(THINK_COLOR)}}

/* --- Turn chips (embedded in agent bubble meta) --- */
.turn-chip{display:inline-flex;align-items:center;gap:3px;background:${rgba(THINK_COLOR, 0.08)};
    border-radius:10px;padding:2px 8px;font-size:0.78em;color:${rgb(THINK_COLOR)}}
.turn-chip:hover{background:${rgba(THINK_COLOR, 0.16)}}
.turn-chip.tool{color:${rgb(JBColor(Color(0x59, 0x8C, 0x4D), Color(0x6A, 0x9F, 0x59)))};background:${
            rgba(
                JBColor(
                    Color(
                        0x59,
                        0x8C,
                        0x4D
                    ), Color(0x6A, 0x9F, 0x59)
                ), 0.08
            )
        }}
.turn-chip.tool.failed{color:rgb(220,80,80);background:rgba(220,80,80,0.08)}
.turn-chip.tool:hover{background:${rgba(JBColor(Color(0x59, 0x8C, 0x4D), Color(0x6A, 0x9F, 0x59)), 0.16)}}
.turn-chip.tool.failed:hover{background:rgba(220,80,80,0.16)}
.turn-chip.ctx:hover{background:${rgba(USER_COLOR, 0.16)}}
.turn-chip.err:hover{background:rgba(255,0,0,0.12)}
.turn-chip.ctx{color:${rgb(USER_COLOR)};background:${rgba(USER_COLOR, 0.08)}}
.turn-chip.err{color:red;background:rgba(255,0,0,0.06)}
.turn-chip.stats{color:${rgb(AGENT_COLOR)};background:${rgba(AGENT_COLOR, 0.08)};cursor:default;position:relative}
.turn-chip.stats:hover{background:${rgba(AGENT_COLOR, 0.16)}}
.turn-chip.stats:hover::after{content:attr(data-tip);position:absolute;bottom:calc(100% + 4px);right:0;
    background:rgb(50,50,54);color:${rgb(THINK_COLOR)};padding:3px 8px;border-radius:4px;font-size:0.85em;white-space:nowrap;z-index:10;
    pointer-events:none;box-shadow:0 2px 6px rgba(0,0,0,0.3)}
.turn-hidden{display:none}

/* --- Status entries --- */
.status-row{padding:4px 12px;margin:2px 0;font-size:0.88em;border-radius:6px}
.status-row.error{color:red;background:rgba(255,0,0,0.05)}
.status-row.info{color:${rgb(THINK_COLOR)};background:${rgba(THINK_COLOR, 0.04)}}

/* --- Session separator --- */
.session-sep{display:flex;align-items:center;gap:10px;margin:16px 0;opacity:0.6}
.session-sep-line{flex:1;height:1px;background:${rgba(THINK_COLOR, 0.3)}}
.session-sep-label{font-size:0.78em;color:${rgb(THINK_COLOR)};white-space:nowrap}

/* --- Placeholder --- */
.placeholder{color:${rgb(THINK_COLOR)};padding:20px 12px;text-align:center;white-space:pre-wrap;
    font-size:0.95em}

/* --- Processing indicator (bouncing dots) --- */
.processing-indicator{display:flex;align-items:center;gap:4px;padding:10px 16px;margin:4px 0}
.processing-dot{width:6px;height:6px;border-radius:50%;background:${rgb(AGENT_COLOR)};opacity:0.4;
    animation:bounce 1.4s ease-in-out infinite}
.processing-dot:nth-child(2){animation-delay:0.2s}
.processing-dot:nth-child(3){animation-delay:0.4s}
@keyframes bounce{0%,80%,100%{transform:scale(1);opacity:0.4}40%{transform:scale(1.3);opacity:1}}

/* --- Markdown content --- */
h2,h3,h4,h5{margin:8px 0 4px 0}
p{margin:3px 0}
a{color:${rgb(linkColor)};text-decoration:none;cursor:pointer}
a:hover{text-decoration:underline}
code{background:${rgb(codeBg)};padding:2px 5px;border-radius:4px;
     font-family:'JetBrains Mono',monospace;font-size:${font.size - 3}pt}
pre{background:${rgb(codeBg)};padding:10px;border-radius:6px;margin:6px 0;overflow-x:auto}
pre code{background:none;padding:0;border-radius:0;display:block}
table{border-collapse:collapse;margin:6px 0}
th,td{border:1px solid ${rgb(tblBorder)};padding:4px 10px;text-align:left}
th{background:${rgb(thBg)};font-weight:600}
tr:nth-child(even) td{background:${rgb(codeBg)}}
ul,ol{margin:4px 0;padding-left:22px}
li{margin:2px 0}
b,strong{font-weight:600}
</style></head><body>
<div id="container"></div>
<script>
var autoScroll=true;
window.addEventListener('scroll',function(){
  autoScroll=(window.innerHeight+window.scrollY>=document.body.scrollHeight-20);
});
function scrollIfNeeded(){if(autoScroll)window.scrollTo(0,document.body.scrollHeight)}
function b64(s){var r=atob(s),b=new Uint8Array(r.length);for(var i=0;i<r.length;i++)b[i]=r.charCodeAt(i);return new TextDecoder().decode(b)}
function toggleTool(id){
  var el=document.getElementById(id);if(!el)return;
  if(el.dataset.chipOwned&&!el.classList.contains('collapsed')){
    el.classList.add('turn-hidden','collapsed');
    el.classList.remove('chip-expanded');
    var btn=el.querySelector('.chip-close');if(btn)btn.remove();
    var chip=document.querySelector('[data-chip-for="'+id+'"]');
    if(chip)chip.style.opacity='1';
    return;
  }
  el.classList.toggle('collapsed');
  el.querySelector('.caret').textContent=el.classList.contains('collapsed')?'\u25B8':'\u25BE';
}
function toggleThinking(id){
  var el=document.getElementById(id);if(!el)return;
  if(el.dataset.chipOwned&&!el.classList.contains('collapsed')){
    el.classList.add('turn-hidden','collapsed');
    el.classList.remove('chip-expanded');
    var btn=el.querySelector('.chip-close');if(btn)btn.remove();
    var chip=document.querySelector('[data-chip-for="'+id+'"]');
    if(chip)chip.style.opacity='1';
    return;
  }
  el.classList.toggle('collapsed');
  el.querySelector('.caret').textContent=el.classList.contains('collapsed')?'\u25B8':'\u25BE';
}
function toggleMeta(el){
  var row=el.parentElement;
  var m=row?row.querySelector('.meta'):null;
  if(m)m.classList.toggle('show');
}
function finalizeTurn(stats){
  var items=document.querySelectorAll('.thinking-section:not(.turn-hidden),.tool-section:not(.turn-hidden),.context-section:not(.turn-hidden),.status-row:not(.turn-hidden)');
  // Find the last agent-row for the stats chip
  var lastBubbles=document.querySelectorAll('.agent-bubble');
  var lastBubble=lastBubbles.length>0?lastBubbles[lastBubbles.length-1]:null;
  var lastRow=lastBubble?lastBubble.parentElement:null;
  var lastMeta=lastRow?lastRow.querySelector('.meta'):null;
  items.forEach(function(el){
    var targetMeta=null;
    var sib=el.nextElementSibling;
    while(sib){
      if(sib.classList.contains('agent-row')){targetMeta=sib.querySelector('.meta');break;}
      sib=sib.nextElementSibling;
    }
    if(!targetMeta)targetMeta=lastMeta;
    if(!targetMeta)return;
    var chip=document.createElement('span');
    var elId=el.id||'';
    if(el.classList.contains('thinking-section')){
      chip.className='turn-chip';chip.textContent='💭 Thought';
    } else if(el.classList.contains('tool-section')){
      var lbl=el.querySelector('.collapse-label');
      var icon=el.querySelector('.collapse-icon');
      var failed=icon&&icon.style.color==='red';
      chip.className='turn-chip tool'+(failed?' failed':'');
      chip.textContent=(lbl?lbl.textContent:'Tool');
    } else if(el.classList.contains('context-section')){
      chip.className='turn-chip ctx';chip.textContent='📎 Context';
    } else if(el.classList.contains('status-row')&&el.classList.contains('error')){
      chip.className='turn-chip err';chip.textContent='✖ '+el.textContent.trim().substring(0,60);
    } else return;
    el.classList.add('turn-hidden');
    el.dataset.chipOwned='1';
    chip.dataset.chipFor=elId;
    chip.style.cursor='pointer';
    function closeSection(){
      el.classList.add('turn-hidden','collapsed');
      el.classList.remove('chip-expanded');
      chip.style.opacity='1';
      var btn=el.querySelector('.chip-close');if(btn)btn.remove();
    }
    chip.onclick=function(ev){
      ev.stopPropagation();
      if(el.classList.contains('turn-hidden')){
        el.classList.remove('turn-hidden','collapsed');
        el.classList.add('chip-expanded');
        chip.style.opacity='0.5';
        var cc=el.querySelector('.collapse-content');
        if(cc&&!cc.querySelector('.chip-close')){
          var btn=document.createElement('span');btn.className='chip-close';btn.textContent='✕';
          btn.onclick=function(e){e.stopPropagation();closeSection()};
          cc.insertBefore(btn,cc.firstChild);
        }
      } else { closeSection(); }
    };
    targetMeta.appendChild(chip);
  });
  // Update stats chip on the prompt bubble with final tool count
  if(stats&&stats.mult){
    var existing=document.getElementById('turn-stats');
    if(existing){
      var label=stats.mult;
      existing.textContent=label;
      var short=stats.model.split('/').pop().substring(0,30);
      existing.setAttribute('data-tip',short+' · '+stats.tools+' tool'+(stats.tools!=1?'s':''));
      existing.removeAttribute('id');
    }
  }
  scrollIfNeeded();
}
function collapseToolToChip(elId){
  var el=document.getElementById(elId);
  if(!el||el.dataset.chipOwned)return;
  var targetMeta=null;
  var sib=el.nextElementSibling;
  while(sib){
    if(sib.classList.contains('agent-row')){targetMeta=sib.querySelector('.meta');break;}
    sib=sib.nextElementSibling;
  }
  if(!targetMeta){
    var lastBubbles=document.querySelectorAll('.agent-bubble');
    var lb=lastBubbles.length>0?lastBubbles[lastBubbles.length-1]:null;
    var lr=lb?lb.parentElement:null;
    targetMeta=lr?lr.querySelector('.meta'):null;
  }
  if(!targetMeta)return;
  var lbl=el.querySelector('.collapse-label');
  var icon=el.querySelector('.collapse-icon');
  var failed=icon&&icon.style.color==='red';
  var chip=document.createElement('span');
  chip.className='turn-chip tool'+(failed?' failed':'');
  chip.textContent=(lbl?lbl.textContent:'Tool');
  el.classList.add('turn-hidden');
  el.dataset.chipOwned='1';
  chip.dataset.chipFor=elId;
  chip.style.cursor='pointer';
  function closeSection(){
    el.classList.add('turn-hidden','collapsed');
    el.classList.remove('chip-expanded');
    chip.style.opacity='1';
    var btn=el.querySelector('.chip-close');if(btn)btn.remove();
  }
  chip.onclick=function(ev){
    ev.stopPropagation();
    if(el.classList.contains('turn-hidden')){
      el.classList.remove('turn-hidden','collapsed');
      el.classList.add('chip-expanded');
      chip.style.opacity='0.5';
      var cc=el.querySelector('.collapse-content');
      if(cc&&!cc.querySelector('.chip-close')){
        var btn=document.createElement('span');btn.className='chip-close';btn.textContent='\u2715';
        btn.onclick=function(e){e.stopPropagation();closeSection()};
        cc.insertBefore(btn,cc.firstChild);
      }
    } else { closeSection(); }
  };
  targetMeta.appendChild(chip);
  scrollIfNeeded();
}
document.addEventListener('click',function(e){
  var el=e.target;
  while(el&&el.tagName!=='A')el=el.parentElement;
  if(el&&el.getAttribute('href')&&el.getAttribute('href').indexOf('openfile://')===0){
    e.preventDefault();$fileHandler
  }
});
var _lastCursor='';
document.addEventListener('mouseover',function(e){
  var el=e.target;var c='default';
  if(el.closest('a,.collapse-header,.turn-chip,.chip-close,.prompt-ctx-chip'))c='pointer';
  else if(el.closest('p,pre,code,li,td,th,.collapse-content,.streaming'))c='text';
  if(c!==_lastCursor){_lastCursor=c;$cursorBridgeJs}
});
</script></body></html>"""
    }

    // --- Markdown to HTML ---

    private fun markdownToHtml(text: String): String {
        val lines = text.lines()
        val sb = StringBuilder()
        var i = 0;
        var inCode = false;
        var inTable = false;
        var firstTR = true;
        var inList = false

        while (i < lines.size) {
            val line = lines[i];
            val t = line.trim()

            if (t.startsWith("```")) {
                if (inCode) {
                    sb.append("</code></pre>"); inCode = false
                } else {
                    if (inList) {
                        sb.append("</ul>"); inList = false
                    }
                    if (inTable) {
                        sb.append("</table>"); inTable = false
                    }
                    sb.append("<pre><code>"); inCode = true
                }
                i++; continue
            }
            if (inCode) {
                sb.append(escapeHtml(line)).append("\n"); i++; continue
            }

            val hm = Regex("^(#{1,4})\\s+(.+)").find(t)
            if (hm != null) {
                if (inList) {
                    sb.append("</ul>"); inList = false
                }
                if (inTable) {
                    sb.append("</table>"); inTable = false
                }
                val lv = hm.groupValues[1].length + 1
                sb.append("<h$lv>").append(formatInline(hm.groupValues[2])).append("</h$lv>")
                i++; continue
            }

            if (t.startsWith("|") && t.endsWith("|") && t.count { it == '|' } >= 3) {
                if (inList) {
                    sb.append("</ul>"); inList = false
                }
                if (t.replace(Regex("[|\\-: ]"), "").isEmpty()) {
                    i++; continue
                }
                if (!inTable) {
                    sb.append("<table>"); inTable = true; firstTR = true
                }
                val cells = t.split("|").drop(1).dropLast(1).map { it.trim() }
                val tag = if (firstTR) "th" else "td"
                sb.append("<tr>"); cells.forEach { sb.append("<$tag>").append(formatInline(it)).append("</$tag>") }
                sb.append("</tr>"); firstTR = false; i++; continue
            }
            if (inTable) {
                sb.append("</table>"); inTable = false
            }

            if (t.startsWith("- ") || t.startsWith("* ")) {
                if (!inList) {
                    sb.append("<ul>"); inList = true
                }
                sb.append("<li>").append(formatInline(t.removePrefix("- ").removePrefix("* "))).append("</li>")
                i++; continue
            }
            if (inList) {
                sb.append("</ul>"); inList = false
            }

            if (t.isEmpty()) {
                i++; continue
            }
            sb.append("<p>").append(formatInline(line)).append("</p>"); i++
        }

        if (inCode) sb.append("</code></pre>")
        if (inTable) sb.append("</table>")
        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    private fun formatInline(text: String): String {
        val result = StringBuilder()
        var lastEnd = 0
        for (match in Regex("`([^`]+)`").findAll(text)) {
            result.append(formatNonCode(text.substring(lastEnd, match.range.first)))
            val content = match.groupValues[1]
            val resolved = resolveFileReference(content)
            if (resolved != null) {
                val href = resolved.first + if (resolved.second != null) ":${resolved.second}" else ""
                result.append("<a href='openfile://$href'><code>${escapeHtml(content)}</code></a>")
            } else {
                result.append("<code>${escapeHtml(content)}</code>")
            }
            lastEnd = match.range.last + 1
        }
        result.append(formatNonCode(text.substring(lastEnd)))
        return result.toString()
    }

    private fun formatNonCode(text: String): String {
        var html = escapeHtml(text)
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        html = FILE_PATH_REGEX.replace(html) { m ->
            val pathPart = m.value.split(":")[0]
            val line = m.value.split(":").getOrNull(1)?.toIntOrNull()
            val resolved = resolveFilePath(pathPart)
            if (resolved != null) "<a href='openfile://$resolved${if (line != null) ":$line" else ""}'>${m.value}</a>"
            else m.value
        }
        return html
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
        ReadAction.compute<String?, Throwable> {
            val files = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            if (files.size == 1) files.first().path else null
        }
    } catch (_: Exception) {
        null
    }
}
