package com.github.copilot.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * Exports a conversation (list of [ChatConsolePanel.EntryData]) to plain text,
 * compressed summary, or a self-contained HTML document.
 */
internal class ConversationExporter(private val entries: List<ChatConsolePanel.EntryData>) {

    fun getConversationText(): String {
        val sb = StringBuilder()
        for (e in entries) when (e) {
            is ChatConsolePanel.EntryData.Prompt -> sb.appendLine(">>> ${e.text}")
            is ChatConsolePanel.EntryData.Text -> {
                sb.append(e.raw); sb.appendLine()
            }

            is ChatConsolePanel.EntryData.Thinking -> sb.appendLine("[thinking] ${e.raw}")
            is ChatConsolePanel.EntryData.ToolCall -> {
                val baseName = e.title.substringAfterLast("-")
                val info = ChatConsolePanel.TOOL_DISPLAY_INFO[e.title] ?: ChatConsolePanel.TOOL_DISPLAY_INFO[baseName]
                val name = info?.displayName ?: e.title
                sb.appendLine("\uD83D\uDD27 $name")
                if (e.arguments != null) sb.appendLine("  params: ${e.arguments}")
            }

            is ChatConsolePanel.EntryData.SubAgent -> {
                val info = ChatConsolePanel.SUB_AGENT_INFO[e.agentType]
                sb.appendLine("${info?.displayName ?: e.agentType}: ${e.description}")
            }

            is ChatConsolePanel.EntryData.ContextFiles -> sb.appendLine(
                "\uD83D\uDCCE ${e.files.size} context file(s): ${
                    e.files.joinToString(
                        ", "
                    ) { it.first }
                }"
            )

            is ChatConsolePanel.EntryData.Status -> sb.appendLine("${e.icon} ${e.message}")
            is ChatConsolePanel.EntryData.SessionSeparator -> sb.appendLine("--- Previous session \uD83D\uDCC5 ${e.timestamp} ---")
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
            is ChatConsolePanel.EntryData.Prompt -> sb.appendLine("User: ${e.text}")
            is ChatConsolePanel.EntryData.Text -> {
                val raw = e.raw.toString().trim()
                if (raw.isNotEmpty()) {
                    val truncated = if (raw.length > 600) raw.take(600) + "...[truncated]" else raw
                    sb.appendLine("Agent: $truncated")
                }
            }

            is ChatConsolePanel.EntryData.ToolCall -> {
                val baseName = e.title.substringAfterLast("-")
                val info = ChatConsolePanel.TOOL_DISPLAY_INFO[e.title] ?: ChatConsolePanel.TOOL_DISPLAY_INFO[baseName]
                val name = info?.displayName ?: e.title
                sb.appendLine("Tool: $name")
            }

            is ChatConsolePanel.EntryData.SubAgent -> {
                val info = ChatConsolePanel.SUB_AGENT_INFO[e.agentType]
                sb.appendLine("Tool: ${info?.displayName ?: e.agentType} — ${e.description}")
            }

            is ChatConsolePanel.EntryData.ContextFiles -> sb.appendLine("Context: ${e.files.joinToString(", ") { it.first }}")
            is ChatConsolePanel.EntryData.SessionSeparator -> sb.appendLine("--- ${e.timestamp} ---")
            is ChatConsolePanel.EntryData.Thinking -> { /* Thinking entries are rendered in HTML only */
            }

            is ChatConsolePanel.EntryData.Status -> { /* Status entries are rendered in HTML only */
            }
        }
        val result = sb.toString()
        if (result.length <= maxChars) return result
        return "[Previous conversation summary - trimmed to recent]\n..." +
                result.substring(result.length - maxChars + 60)
    }

    /** Returns the conversation as a self-contained HTML document */
    fun getConversationHtml(): String {
        val sb = StringBuilder()
        sb.append(buildExportCss())
        for (e in entries) sb.append(renderExportEntry(e))
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun buildExportCss(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val codeBg = UIManager.getColor("Editor.backgroundColor")
            ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder = UIManager.getColor("TableCell.borderColor")
            ?: JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg = UIManager.getColor("TableHeader.background")
            ?: JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val linkColor = UIManager.getColor(ChatConsolePanel.LINK_COLOR_KEY)
            ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))

        return """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:'${font.family}',system-ui,sans-serif;font-size:${font.size}pt;color:${rgb(fg)};padding:16px;max-width:820px;margin:0 auto}
.prompt{margin:12px 0 4px}
.prompt-b{display:inline-block;background:${rgba(ChatConsolePanel.USER_COLOR, 0.12)};border:1px solid ${
            rgba(
                ChatConsolePanel.USER_COLOR,
                0.3
            )
        };border-radius:16px 16px 16px 4px;padding:8px 14px;color:${rgb(ChatConsolePanel.USER_COLOR)};font-weight:600}
.response{margin:4px 0;line-height:1.55}
.thinking{background:${rgba(ChatConsolePanel.THINK_COLOR, 0.06)};border:1px solid ${
            rgba(
                ChatConsolePanel.THINK_COLOR,
                0.2
            )
        };border-radius:4px 16px 16px 16px;padding:6px 12px;margin:4px 0;font-size:0.88em;color:${rgb(ChatConsolePanel.THINK_COLOR)}}
.tool{display:inline-flex;align-items:center;gap:6px;background:${rgba(ChatConsolePanel.TOOL_COLOR, 0.1)};border:1px solid ${
            rgba(
                ChatConsolePanel.TOOL_COLOR,
                0.3
            )
        };border-radius:20px;padding:3px 12px;margin:2px 0;font-size:0.88em;color:${rgb(ChatConsolePanel.TOOL_COLOR)}}
.context{font-size:0.88em;color:${rgb(ChatConsolePanel.USER_COLOR)};margin:2px 0}
.context summary{cursor:pointer;padding:4px 0}
.context .ctx-file{padding:2px 0;padding-left:8px}
.context .ctx-file a{color:${rgb(linkColor)};text-decoration:none}
.status{padding:4px 8px;margin:2px 0;font-size:0.88em}
.status.error{color:red} .status.info{color:${rgb(ChatConsolePanel.THINK_COLOR)}}
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
    }

    private fun renderExportEntry(e: ChatConsolePanel.EntryData): String = when (e) {
        is ChatConsolePanel.EntryData.Prompt -> "<div class='prompt'><span class='prompt-b'>${escapeHtml(e.text)}</span></div>\n"
        is ChatConsolePanel.EntryData.Text -> "<div class='response'>${markdownToHtml(e.raw.toString())}</div>\n"
        is ChatConsolePanel.EntryData.Thinking -> "<details class='thinking'><summary>\uD83D\uDCAD Thought process</summary><pre>${
            escapeHtml(
                e.raw.toString()
            )
        }</pre></details>\n"

        is ChatConsolePanel.EntryData.ToolCall -> renderExportToolCall(e)
        is ChatConsolePanel.EntryData.SubAgent -> renderExportSubAgent(e)
        is ChatConsolePanel.EntryData.ContextFiles -> renderExportContextFiles(e)
        is ChatConsolePanel.EntryData.Status -> "<div class='status ${if (e.icon == ChatConsolePanel.ICON_ERROR) "error" else "info"}'>${e.icon} ${
            escapeHtml(
                e.message
            )
        }</div>\n"

        is ChatConsolePanel.EntryData.SessionSeparator -> "<hr style='border:none;border-top:1px solid #555;margin:16px 0'><div style='text-align:center;font-size:0.85em;color:#888'>Previous session \uD83D\uDCC5 ${
            escapeHtml(
                e.timestamp
            )
        }</div>\n"
    }

    private fun renderExportToolCall(e: ChatConsolePanel.EntryData.ToolCall): String {
        val baseName = e.title.substringAfterLast("-")
        val info = ChatConsolePanel.TOOL_DISPLAY_INFO[e.title] ?: ChatConsolePanel.TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: e.title
        val sb = StringBuilder("<details class='tool'><summary>\u2692 ${escapeHtml(displayName)}</summary>")
        if (info?.description != null) sb.append("<div style='font-style:italic;margin:4px 0'>${escapeHtml(info.description)}</div>")
        if (e.arguments != null) sb.append("<div style='margin:4px 0'><b>Parameters:</b><pre><code>${escapeHtml(e.arguments)}</code></pre></div>")
        sb.append("</details>\n")
        return sb.toString()
    }

    private fun renderExportSubAgent(e: ChatConsolePanel.EntryData.SubAgent): String {
        val info = ChatConsolePanel.SUB_AGENT_INFO[e.agentType]
        val name = info?.displayName ?: e.agentType
        val sb = StringBuilder()
        if (e.prompt != null) sb.append("<div class='response'><b>@$name</b> ${escapeHtml(e.prompt)}</div>\n")
        if (e.result != null) sb.append("<div class='response'>${markdownToHtml(e.result!!)}</div>\n")
        else sb.append("<div class='response'><b>@$name</b> \u2014 ${escapeHtml(e.description)}</div>\n")
        return sb.toString()
    }

    private fun renderExportContextFiles(e: ChatConsolePanel.EntryData.ContextFiles): String {
        val label = "${e.files.size} context file${if (e.files.size != 1) "s" else ""} attached"
        val sb = StringBuilder("<details class='context'><summary>\uD83D\uDCCE $label</summary>")
        e.files.forEach { (name, _) -> sb.append("<div class='ctx-file'><code>${escapeHtml(name)}</code></div>") }
        sb.append("</details>\n")
        return sb.toString()
    }

    companion object {
        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

        private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
        private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"

        private fun markdownToHtml(text: String): String =
            MarkdownRenderer.markdownToHtml(text)
    }
}
