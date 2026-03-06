package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git operation output (push, pull, fetch, merge, rebase,
 * cherry-pick, reset, revert, unstage, remote) as a status header
 * with the raw git output in a monospace code block.
 */
internal object GitOperationRenderer : ToolResultRenderer {

    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val FAIL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))

    private val SUCCESS_LINE = Regex("""^✓\s+(.+)""")
    private val ERROR_LINE = Regex("""^Error:\s+(.+)""", RegexOption.DOT_MATCHES_ALL)
    private val CONFLICT_LINE = Regex("""(?i)conflict|CONFLICT""")
    private val ALREADY_UP_TO_DATE = Regex("""(?i)already up[- ]to[- ]date""")
    private val NOTHING_TO = Regex("""(?i)nothing to""")
    private val FETCH_COMPLETE = Regex("""^Fetch completed successfully""")

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val firstLine = lines.first().trim()

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()

        when {
            ERROR_LINE.containsMatchIn(firstLine) -> {
                val msg = ERROR_LINE.find(firstLine)!!.groupValues[1]
                headerRow.add(JBLabel("Error").apply {
                    icon = ToolIcons.FAILURE
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = FAIL_COLOR
                })
                panel.add(headerRow)
                panel.add(ToolRenderers.codeBlock(msg))
                return panel
            }
            SUCCESS_LINE.containsMatchIn(firstLine) -> {
                headerRow.add(JBLabel(SUCCESS_LINE.find(firstLine)!!.groupValues[1]).apply {
                    icon = ToolIcons.SUCCESS
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = SUCCESS_COLOR
                })
                panel.add(headerRow)
                val body = lines.drop(1).joinToString("\n").trim()
                if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
                return panel
            }
            CONFLICT_LINE.containsMatchIn(text) -> {
                headerRow.add(JBLabel("Conflicts detected").apply {
                    icon = ToolIcons.WARNING
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = WARN_COLOR
                })
            }
            ALREADY_UP_TO_DATE.containsMatchIn(firstLine) || NOTHING_TO.containsMatchIn(firstLine) -> {
                headerRow.add(JBLabel(firstLine).apply {
                    icon = ToolIcons.SUCCESS
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = SUCCESS_COLOR
                })
                panel.add(headerRow)
                return panel
            }
            FETCH_COMPLETE.containsMatchIn(firstLine) -> {
                headerRow.add(JBLabel("Fetch completed").apply {
                    icon = ToolIcons.SUCCESS
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = SUCCESS_COLOR
                })
                panel.add(headerRow)
                val body = lines.drop(1).joinToString("\n").trim()
                if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
                return panel
            }
            else -> {
                headerRow.add(JBLabel("Completed").apply {
                    icon = ToolIcons.SUCCESS
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = SUCCESS_COLOR
                })
            }
        }

        panel.add(headerRow)
        if (text.isNotEmpty()) panel.add(ToolRenderers.codeBlock(text))
        return panel
    }
}
