package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders git status output as a structured card with branch header,
 * staged/unstaged/untracked sections, and colored file badges.
 */
internal object GitStatusRenderer : ToolResultRenderer {

    private data class StatusFiles(
        val staged: List<Pair<String, String>>,
        val unstaged: List<Pair<String, String>>,
        val untracked: List<String>,
        val conflicted: List<String>,
    )

    override fun render(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val sb = StringBuilder("<div class='git-status-result'>")
        appendBranchHeader(sb, lines)

        val files = categorizeFiles(lines)
        if (files.staged.isEmpty() && files.unstaged.isEmpty()
            && files.untracked.isEmpty() && files.conflicted.isEmpty()
        ) {
            sb.append("<div class='git-status-clean'>Nothing to commit, working tree clean</div>")
        }

        appendSection(sb, files.conflicted, "git-status-conflict", "Conflicts") { path ->
            "<span class='git-file-badge git-file-conflict'>U</span> <span class='git-file-path'>${esc(path)}</span>"
        }
        appendBadgedSection(sb, files.staged, "git-status-staged", "Staged")
        appendBadgedSection(sb, files.unstaged, "git-status-unstaged", "Changes")
        appendSection(sb, files.untracked, "git-status-untracked", "Untracked") { path ->
            "<span class='git-file-badge git-file-untracked'>?</span> <span class='git-file-path'>${esc(path)}</span>"
        }

        sb.append("</div>")
        return sb.toString()
    }

    private fun appendBranchHeader(sb: StringBuilder, lines: List<String>) {
        val branchLine = lines.firstOrNull { it.startsWith("##") } ?: return
        val branchInfo = branchLine.removePrefix("## ").trim()
        val branchName = branchInfo.split("...").first()
        val tracking = if ("..." in branchInfo) branchInfo.substringAfter("...") else null
        sb.append("<div class='git-status-branch'>")
        sb.append("<span class='git-status-branch-name'>${esc(branchName)}</span>")
        if (tracking != null) {
            sb.append(" <span class='git-status-tracking'>${esc(tracking)}</span>")
        }
        sb.append("</div>")
    }

    private fun categorizeFiles(lines: List<String>): StatusFiles {
        val staged = mutableListOf<Pair<String, String>>()
        val unstaged = mutableListOf<Pair<String, String>>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("##") || line.isBlank() || line.length < 4) continue
            val x = line[0]
            val y = line[1]
            val path = line.substring(3).trim()
            categorizeFile(x, y, path, staged, unstaged, untracked, conflicted)
        }
        return StatusFiles(staged, unstaged, untracked, conflicted)
    }

    private fun categorizeFile(
        x: Char, y: Char, path: String,
        staged: MutableList<Pair<String, String>>,
        unstaged: MutableList<Pair<String, String>>,
        untracked: MutableList<String>,
        conflicted: MutableList<String>,
    ) {
        if (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')) {
            conflicted.add(path); return
        }
        if (x == '?' && y == '?') { untracked.add(path); return }
        if (x != ' ' && x != '?') staged.add(statusBadge(x) to path)
        if (y != ' ' && y != '?') unstaged.add(statusBadge(y) to path)
    }

    private fun statusBadge(code: Char): String {
        val (label, cls) = when (code) {
            'M' -> "M" to "git-file-mod"
            'A' -> "A" to "git-file-add"
            'D' -> "D" to "git-file-del"
            'R' -> "R" to "git-file-rename"
            'C' -> "C" to "git-file-rename"
            'T' -> "T" to "git-file-mod"
            else -> code.toString() to "git-file-mod"
        }
        return "<span class='git-file-badge $cls'>$label</span>"
    }

    private fun <T> appendSection(
        sb: StringBuilder, items: List<T>, headerCls: String, label: String, renderItem: (T) -> String,
    ) {
        if (items.isEmpty()) return
        sb.append("<div class='git-status-section'>")
        sb.append("<div class='git-status-section-header $headerCls'>$label (${items.size})</div>")
        for (item in items) sb.append("<div class='git-file-entry'>${renderItem(item)}</div>")
        sb.append("</div>")
    }

    private fun appendBadgedSection(
        sb: StringBuilder, items: List<Pair<String, String>>, headerCls: String, label: String,
    ) {
        appendSection(sb, items, headerCls, label) { (badge, path) ->
            "$badge <span class='git-file-path'>${esc(path)}</span>"
        }
    }
}
