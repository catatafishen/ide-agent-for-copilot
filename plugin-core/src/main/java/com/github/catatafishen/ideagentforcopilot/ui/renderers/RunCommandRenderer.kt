package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders run_command output with a status header (success/fail),
 * exit code, and the command output in a monospace block.
 */
internal object RunCommandRenderer : ToolResultRenderer {

    private val SUCCESS_HEADER = Regex("""^✅\s*Command succeeded""")
    private val FAIL_HEADER = Regex("""^❌\s*Command failed\s*\(exit code (\d+)\)""")
    private val TIMEOUT_HEADER = Regex("""^Command timed out after (\d+) seconds""")
    private val PAGINATION_NOTE = Regex("""\(showing last \d+ chars.*\)""")

    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val FAIL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first().trim()
        val successMatch = SUCCESS_HEADER.find(firstLine)
        val failMatch = FAIL_HEADER.find(firstLine)
        val timeoutMatch = TIMEOUT_HEADER.find(firstLine)

        if (successMatch == null && failMatch == null && timeoutMatch == null) return null

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        when {
            successMatch != null -> {
                headerRow.add(JBLabel("✓ Command succeeded").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = SUCCESS_COLOR
                })
            }
            failMatch != null -> {
                headerRow.add(JBLabel("✗ Command failed").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = FAIL_COLOR
                })
                headerRow.add(ToolRenderers.mutedLabel("exit code ${failMatch.groupValues[1]}"))
            }
            timeoutMatch != null -> {
                headerRow.add(JBLabel("⏱ Timed out").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = WARN_COLOR
                })
                headerRow.add(ToolRenderers.mutedLabel("after ${timeoutMatch.groupValues[1]}s"))
            }
        }
        panel.add(headerRow)

        val body = lines.drop(1)
            .filterNot { PAGINATION_NOTE.containsMatchIn(it) }
            .joinToString("\n")
            .trim()

        if (body.isNotEmpty()) {
            panel.add(ToolRenderers.codeBlock(body))
        }

        return panel
    }
}
