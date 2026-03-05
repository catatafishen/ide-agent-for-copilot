package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders git log output as a structured commit list with clickable hashes,
 * author info, and relative dates. Supports oneline, short, and medium formats.
 */
internal object GitLogRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first().trim()
        return when {
            firstLine.startsWith("commit ") -> renderMediumLog(lines)
            SHORT_PATTERN.matches(firstLine) -> renderShortLog(lines)
            ONELINE_PATTERN.matches(firstLine) -> renderOnelineLog(lines)
            else -> null
        }
    }

    private val SHORT_PATTERN = Regex("""^[a-f0-9]{7,40}\s+.+\(.+,\s*.+\)$""")
    private val ONELINE_PATTERN = Regex("""^[a-f0-9]{7,40}\s+.+""")

    private fun renderOnelineLog(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-log-result'>")
        for (line in lines) {
            if (line.isBlank()) continue
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx < 0) continue
            val hash = line.substring(0, spaceIdx).trim()
            val message = line.substring(spaceIdx + 1).trim()
            appendEntry(sb, hash, message, null)
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderShortLog(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-log-result'>")
        val pattern = Regex("""^([a-f0-9]{7,40})\s+(.+)\((.+),\s*(.+)\)$""")
        for (line in lines) {
            if (line.isBlank()) continue
            val match = pattern.find(line.trim())
            if (match != null) {
                val hash = match.groupValues[1]
                val message = match.groupValues[2].trim()
                val meta = "${match.groupValues[3].trim()}, ${match.groupValues[4].trim()}"
                appendEntry(sb, hash, message, meta)
            } else {
                sb.append("<div class='git-log-entry'><span class='git-log-message'>${esc(line.trim())}</span></div>")
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderMediumLog(lines: List<String>): String {
        data class Entry(var hash: String = "", var author: String = "", var date: String = "", val message: StringBuilder = StringBuilder())

        val sb = StringBuilder("<div class='git-log-result'>")
        var current = Entry()
        var inMessage = false

        fun flush() {
            if (current.hash.isEmpty()) return
            val shortHash = if (current.hash.length > 7) current.hash.substring(0, 7) else current.hash
            val msg = current.message.toString().trim().lines().firstOrNull() ?: ""
            val meta = listOf(current.author, current.date).filter { it.isNotEmpty() }.joinToString(", ")
            appendEntry(sb, shortHash, msg, meta.ifEmpty { null })
            current = Entry()
            inMessage = false
        }

        for (line in lines) {
            when {
                line.startsWith("commit ") -> { flush(); current.hash = line.removePrefix("commit ").trim() }
                line.startsWith("Author:") -> current.author = line.removePrefix("Author:").trim().replace(Regex("""<.*>"""), "").trim()
                line.startsWith("Date:") -> { current.date = line.removePrefix("Date:").trim(); inMessage = true }
                inMessage -> current.message.appendLine(line.trimStart())
            }
        }
        flush()
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendEntry(sb: StringBuilder, hash: String, message: String, meta: String?) {
        sb.append("<div class='git-log-entry'>")
        sb.append("<a href='gitshow://$hash' class='git-log-hash' title='Show commit'>${esc(hash)}</a>")
        if (message.isNotEmpty()) sb.append(" <span class='git-log-message'>${esc(message)}</span>")
        if (!meta.isNullOrEmpty()) sb.append(" <span class='git-log-meta'>${esc(meta)}</span>")
        sb.append("</div>")
    }
}
