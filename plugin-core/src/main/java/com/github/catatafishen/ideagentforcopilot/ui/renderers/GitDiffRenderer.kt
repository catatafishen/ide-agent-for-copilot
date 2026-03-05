package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.parseDiffStats

/**
 * Renders unified diff output with syntax-highlighted additions/deletions,
 * file headers, and hunk markers. Also handles stat-only output.
 */
internal object GitDiffRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val lastNonBlank = lines.lastOrNull { it.isNotBlank() } ?: return null
        if (lastNonBlank.matches(Regex("""\s*\d+ files? changed.*"""))) {
            return renderStatOutput(lines)
        }
        return renderUnifiedDiff(lines)
    }

    // ── Stat-only rendering ───────────────────────────────────

    private fun renderStatOutput(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-diff-result'>")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.matches(Regex("""\d+ files? changed.*"""))) {
                appendStatSummary(sb, trimmed)
            } else {
                appendStatFileLine(sb, trimmed)
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendStatSummary(sb: StringBuilder, line: String) {
        val stats = parseDiffStats(line)
        sb.append("<div class='git-diff-stat-summary'>")
        sb.append("<span class='git-stat-files'>${esc(stats.files)}</span>")
        if (stats.insertions.isNotEmpty()) sb.append(" <span class='git-stat-ins'>+${esc(stats.insertions)}</span>")
        if (stats.deletions.isNotEmpty()) sb.append(" <span class='git-stat-del'>-${esc(stats.deletions)}</span>")
        sb.append("</div>")
    }

    private fun appendStatFileLine(sb: StringBuilder, line: String) {
        val match = Regex("""^\s*(.+?)\s*\|\s*(\d+)\s*([+\-]+)?\s*$""").find(line)
        if (match != null) {
            val path = esc(match.groupValues[1].trim())
            val count = esc(match.groupValues[2])
            val bar = match.groupValues[3]
            sb.append("<div class='git-diff-stat-file'>")
            sb.append("<span class='git-file-path'>$path</span>")
            sb.append(" <span class='git-diff-stat-count'>$count</span> ")
            sb.append(renderStatBar(bar))
            sb.append("</div>")
        } else {
            sb.append("<div class='git-diff-stat-file'><span class='git-file-path'>${esc(line)}</span></div>")
        }
    }

    private fun renderStatBar(bar: String): String {
        if (bar.isEmpty()) return ""
        val sb = StringBuilder("<span class='git-diff-stat-bar'>")
        for (ch in bar) {
            when (ch) {
                '+' -> sb.append("<span class='git-stat-ins'>+</span>")
                '-' -> sb.append("<span class='git-stat-del'>-</span>")
                else -> sb.append(ch)
            }
        }
        sb.append("</span>")
        return sb.toString()
    }

    // ── Unified diff rendering ────────────────────────────────

    private enum class LineType { FILE_HEADER, HUNK, ADD, DEL, CONTEXT, BINARY, SKIP }

    private fun renderUnifiedDiff(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-diff-result'>")
        var inFile = false

        for (line in lines) {
            when (classifyLine(line)) {
                LineType.FILE_HEADER -> {
                    if (inFile) sb.append("</div></div>")
                    inFile = true
                    val filePath = extractFilePath(line)
                    sb.append("<div class='git-diff-file'>")
                    sb.append("<div class='git-diff-file-header'><span class='git-file-path'>${esc(filePath)}</span></div>")
                    sb.append("<div class='git-diff-hunks'>")
                }
                LineType.HUNK -> appendHunkHeader(sb, line)
                LineType.ADD -> sb.append("<div class='git-diff-line git-diff-add'>${esc(line)}</div>")
                LineType.DEL -> sb.append("<div class='git-diff-line git-diff-del'>${esc(line)}</div>")
                LineType.BINARY -> sb.append("<div class='git-diff-line git-diff-meta'>${esc(line)}</div>")
                LineType.CONTEXT -> if (inFile) sb.append("<div class='git-diff-line git-diff-ctx'>${esc(line)}</div>")
                LineType.SKIP -> { /* meta lines */ }
            }
        }
        if (inFile) sb.append("</div></div>")
        sb.append("</div>")
        return sb.toString()
    }

    private fun classifyLine(line: String): LineType = when {
        line.startsWith("diff --git") -> LineType.FILE_HEADER
        line.startsWith("@@") -> LineType.HUNK
        line.startsWith("+") && !line.startsWith("+++") -> LineType.ADD
        line.startsWith("-") && !line.startsWith("---") -> LineType.DEL
        line.startsWith("---") || line.startsWith("+++") -> LineType.SKIP
        line.startsWith("Binary files") -> LineType.BINARY
        isMetaLine(line) -> LineType.SKIP
        else -> LineType.CONTEXT
    }

    private fun isMetaLine(line: String): Boolean =
        line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") ||
            line.startsWith("similarity") || line.startsWith("rename") ||
            line.startsWith("old mode") || line.startsWith("new mode")

    private fun appendHunkHeader(sb: StringBuilder, line: String) {
        val match = Regex("""@@\s*-\d+(?:,\d+)?\s*\+\d+(?:,\d+)?\s*@@(.*)""").find(line)
        val context = match?.groupValues?.get(1)?.trim() ?: ""
        sb.append("<div class='git-diff-hunk-header'>${esc(line.substringBefore("@@", "").let { "$it@@" })} ")
        if (context.isNotEmpty()) sb.append("<span class='git-diff-hunk-ctx'>${esc(context)}</span>")
        sb.append("</div>")
    }

    private fun extractFilePath(diffLine: String): String {
        val match = Regex("""diff --git a/(.+) b/(.+)""").find(diffLine)
        return match?.groupValues?.get(2) ?: diffLine.substringAfter("diff --git ").trim()
    }
}
