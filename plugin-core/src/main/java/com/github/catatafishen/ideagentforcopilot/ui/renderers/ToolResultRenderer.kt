package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.*

/**
 * Interface for custom tool-result renderers in the tool-call popup.
 * Each implementation transforms raw tool output text into a Swing component.
 */
internal fun interface ToolResultRenderer {
    /**
     * Render tool output as a Swing component, or return null to fall back
     * to a default monospace text area.
     */
    fun render(output: String): JComponent?
}

/**
 * Registry of tool-result renderers and shared rendering utilities.
 */
internal object ToolRenderers {

    private val registry: Map<String, ToolResultRenderer> = mapOf(
        // Git
        "git_commit" to GitCommitRenderer,
        "git_status" to GitStatusRenderer,
        "git_diff" to GitDiffRenderer,
        "git_log" to GitLogRenderer,
        "git_show" to GitShowRenderer,
        "git_blame" to GitBlameRenderer,
        "git_branch" to GitBranchRenderer,
        "git_tag" to GitTagRenderer,
        "git_stash" to GitStashRenderer,
        "git_stage" to GitStageRenderer,
        // Build & test
        "build_project" to BuildResultRenderer,
        "run_tests" to TestResultRenderer,
        "list_tests" to ListTestsRenderer,
        "list_run_configurations" to RunConfigRenderer,
        "get_coverage" to CoverageRenderer,
        // Code quality
        "run_inspections" to InspectionResultRenderer,
        "get_compilation_errors" to InspectionResultRenderer,
        "get_highlights" to InspectionResultRenderer,
        "get_problems" to InspectionResultRenderer,
        // Search & navigation
        "search_text" to SearchResultRenderer,
        "search_symbols" to SearchResultRenderer,
        "find_references" to SearchResultRenderer,
        "get_file_outline" to FileOutlineRenderer,
        "get_class_outline" to ClassOutlineRenderer,
        // Navigation
        "go_to_declaration" to GoToDeclarationRenderer,
        "get_type_hierarchy" to TypeHierarchyRenderer,
        // Refactoring
        "refactor" to RefactorRenderer,
        // Project & files
        "list_project_files" to ListProjectFilesRenderer,
        "get_project_info" to ProjectInfoRenderer,
        // Infrastructure
        "run_command" to RunCommandRenderer,
        // HTTP
        "http_request" to HttpRequestRenderer,
        // File I/O
        "intellij_read_file" to ReadFileRenderer,
        "read_file" to ReadFileRenderer,
        "intellij_write_file" to WriteFileRenderer,
        "write_file" to WriteFileRenderer,
        "create_file" to WriteFileRenderer,
        // Symbol editing
        "replace_symbol_body" to ReplaceSymbolRenderer,
        "insert_before_symbol" to ReplaceSymbolRenderer,
        "insert_after_symbol" to ReplaceSymbolRenderer,
    )

    fun get(toolName: String): ToolResultRenderer? = registry[toolName]

    fun hasRenderer(toolName: String): Boolean = toolName in registry

    // ── Shared rendering utilities ────────────────────────────

    private const val MONO_FONT = "JetBrains Mono"

    /**
     * Creates a header panel with icon, count, and label (e.g., "🏷 5 tags").
     */
    fun headerPanel(icon: String, count: Int, label: String): JPanel {
        val header = JBLabel("$icon $count $label").apply {
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
        }
    }

    /**
     * Creates a vertical list panel with standard spacing.
     */
    fun listPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    /**
     * Creates a horizontal row panel for list items.
     */
    fun rowPanel(): JPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 1)).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    /**
     * Creates a monospace JBLabel.
     */
    fun monoLabel(text: String): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
    }

    /**
     * Creates a muted-color JBLabel for secondary information.
     */
    fun mutedLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = blendColor(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.55)
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
    }

    /**
     * Creates a bold monospace badge label (e.g., status codes like "M", "A", "D").
     */
    fun badgeLabel(text: String, color: Color): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size - 1).deriveFont(java.awt.Font.BOLD)
        foreground = color
    }

    /**
     * Creates a read-only code block with monospace font and subtle background.
     */
    fun codeBlock(text: String): JTextArea = JTextArea(text).apply {
        isEditable = false
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
        background = blendColor(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.05)
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(6)
        lineWrap = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    /**
     * Creates a read-only monospace text area for displaying plain text output.
     */
    fun codePanel(text: String): JComponent {
        return JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size - 1)
            background = UIManager.getColor("Editor.backgroundColor")
                ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(6, 8)
        }
    }

    // ── Shared parsing utilities ──────────────────────────────

    data class DiffStats(val files: String, val insertions: String, val deletions: String)

    fun parseDiffStats(line: String): DiffStats {
        val filesMatch = Regex("""\d+ files? changed""").find(line)
        val insMatch = Regex("""(\d+) insertions?\(\+\)""").find(line)
        val delMatch = Regex("""(\d+) deletions?\(-\)""").find(line)
        return DiffStats(
            files = filesMatch?.value ?: line,
            insertions = insMatch?.groupValues?.get(1) ?: "",
            deletions = delMatch?.groupValues?.get(1) ?: "",
        )
    }

    fun blendColor(fg: Color, bg: Color, alpha: Double): Color = Color(
        (fg.red * alpha + bg.red * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.green * alpha + bg.green * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.blue * alpha + bg.blue * (1 - alpha)).toInt().coerceIn(0, 255),
    )
}
