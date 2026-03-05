package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.parseDiffStats

/**
 * Renders a git commit result as a rich card with commit metadata,
 * message, changed files with stats, and a link to show in IDE git tools.
 */
internal object GitCommitRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val headerMatch = Regex("""\[(\S+)\s+([a-f0-9]+)]\s+(.+)""").find(lines[0]) ?: return null
        val branch = esc(headerMatch.groupValues[1])
        val shortHash = esc(headerMatch.groupValues[2])
        val message = esc(headerMatch.groupValues[3])

        val fileLines = mutableListOf<String>()
        var summaryLine = ""
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (line.matches(Regex("""\d+ files? changed.*"""))) summaryLine = line
            else fileLines.add(line)
        }

        val sb = StringBuilder("<div class='git-commit-result'>")

        // Header: hash + branch
        sb.append("<div class='git-commit-header'>")
        sb.append("<span class='git-commit-hash'>")
        sb.append("<a href='gitshow://$shortHash' class='git-commit-link' title='Show commit in IDE'>$shortHash</a>")
        sb.append("</span>")
        sb.append(" <span class='git-commit-branch'>$branch</span>")
        sb.append("</div>")

        sb.append("<div class='git-commit-message'>$message</div>")

        if (summaryLine.isNotEmpty()) {
            val stats = parseDiffStats(summaryLine)
            sb.append("<div class='git-commit-stats'>")
            sb.append("<span class='git-stat-files'>${esc(stats.files)}</span>")
            if (stats.insertions.isNotEmpty()) {
                sb.append(" <span class='git-stat-ins'>+${esc(stats.insertions)}</span>")
            }
            if (stats.deletions.isNotEmpty()) {
                sb.append(" <span class='git-stat-del'>-${esc(stats.deletions)}</span>")
            }
            sb.append("</div>")
        }

        if (fileLines.isNotEmpty()) {
            sb.append("<div class='git-commit-files'>")
            for (fileLine in fileLines) sb.append(renderFileEntry(fileLine))
            sb.append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    private fun renderFileEntry(line: String): String {
        val createMatch = Regex("""create mode \d+ (.+)""").find(line)
        val deleteMatch = Regex("""delete mode \d+ (.+)""").find(line)
        val renameMatch = Regex("""rename (.+) => (.+) \((\d+)%\)""").find(line)

        return when {
            createMatch != null -> {
                val path = esc(createMatch.groupValues[1].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-add'>A</span> <span class='git-file-path'>$path</span></div>"
            }
            deleteMatch != null -> {
                val path = esc(deleteMatch.groupValues[1].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-del'>D</span> <span class='git-file-path'>$path</span></div>"
            }
            renameMatch != null -> {
                val from = esc(renameMatch.groupValues[1].trim())
                val to = esc(renameMatch.groupValues[2].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-rename'>R</span> <span class='git-file-path'>$from → $to</span></div>"
            }
            else -> {
                val path = esc(line.trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-mod'>M</span> <span class='git-file-path'>$path</span></div>"
            }
        }
    }
}
