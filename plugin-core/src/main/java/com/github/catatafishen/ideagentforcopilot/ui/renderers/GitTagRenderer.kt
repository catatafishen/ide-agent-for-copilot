package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for git_tag output.
 * Input: one tag per line from `git tag -l`.
 */
internal object GitTagRenderer : ToolResultRenderer {

    override fun render(output: String): String? {
        val tags = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) return null

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='outline-result'>")

        // Header
        sb.append("<div class='outline-header'>")
        sb.append("<span class='search-icon'>🏷</span> ")
        sb.append("<span class='search-count'>${tags.size}</span> ")
        sb.append("<span class='search-label'>${if (tags.size == 1) "tag" else "tags"}</span>")
        sb.append("</div>")

        // Tags
        sb.append("<div class='outline-section-items'>")
        for (tag in tags) {
            sb.append("<div class='outline-item'>")
            sb.append("<span class='git-file-badge badge-enum'>🏷</span> ")
            sb.append("<code>${e(tag)}</code>")
            sb.append("</div>")
        }
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }
}
