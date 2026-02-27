package com.github.copilot.intellij.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font

/**
 * Help dialog action showing toolbar guide, tool categories, and plugin info.
 * Extracted from AgenticCopilotToolWindowContent for file-size reduction.
 */
internal class HelpAction(private val project: Project) : AnAction(
    "Help", "Show help for all toolbar features and plugin behavior",
    com.intellij.icons.AllIcons.Actions.Help
) {
    private class HelpRow(val icon: javax.swing.Icon, val name: String, val description: String)
    private class HelpToolInfo(val name: String, val description: String)
    private class HelpToolCategory(val title: String, val tools: List<HelpToolInfo>)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val content = buildHelpDialogContent()
        com.intellij.openapi.ui.DialogBuilder(project).apply {
            setTitle("Copilot Bridge \u2014 Help")
            setCenterPanel(content)
            removeAllActions()
            addOkAction()
            show()
        }
    }

    private fun buildToolbarHelpItems(): List<HelpRow?> = listOf(
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Execute,
            "Send",
            "Send your prompt to the agent. Shortcut: Enter. Use Shift+Enter for a new line."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Suspend,
            "Stop",
            "While the agent is working, this replaces Send. Stops the current agent turn."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Actions.AddFile,
            "Attach File",
            "Attach the currently open editor file to your prompt as context."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.AddMulticaret,
            "Attach Selection",
            "Attach the current text selection from the editor to your prompt."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Lightning,
            "Model",
            "Dropdown: choose the AI model. Premium models show a cost multiplier (e.g. \"50\u00d7\")."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.General.Settings,
            "Mode",
            "Dropdown: Agent = autonomous tool use. Plan = conversation only, no tool calls."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Preview,
            "Follow Agent",
            "Toggle: auto-open files in the editor as the agent reads or writes them."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.ReformatCode,
            "Format",
            "Toggle: instruct the agent to auto-format code after editing files."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Compile,
            "Build",
            "Toggle: instruct the agent to build the project before completing its turn."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Nodes.Test,
            "Test",
            "Toggle: instruct the agent to run tests before completing its turn."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Commit,
            "Commit",
            "Toggle: instruct the agent to auto-commit changes before completing its turn."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Nodes.Folder,
            "Project Files",
            "Dropdown: open Instructions, TODO, Agent Definitions, or MCP Server Instructions."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.ToolbarDecorator.Export,
            "Export Chat",
            "Copy the full conversation to clipboard (as text or HTML)."
        ),
        HelpRow(com.intellij.icons.AllIcons.Actions.Help, "Help", "This dialog."),
    )

    private fun buildTitleBarHelpItems(): List<HelpRow> = listOf(
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Restart,
            "New Chat",
            "Start a fresh conversation (top-right of the tool window)."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.General.Settings,
            "Settings",
            "Configure inactivity timeout, max tool calls, and max requests per turn."
        ),
    )

    private fun buildToolCategories(): List<HelpToolCategory> = listOf(
        HelpToolCategory(
            "Code Intelligence", listOf(
                HelpToolInfo("search_symbols", "Search for symbols (classes, methods, fields) by name."),
                HelpToolInfo("get_file_outline", "Get the structural outline of a file."),
                HelpToolInfo("get_class_outline", "Show constructors, methods, fields of any class."),
                HelpToolInfo("find_references", "Find all usages of a symbol across the project."),
                HelpToolInfo("get_type_hierarchy", "Show supertypes and subtypes of a class."),
                HelpToolInfo("go_to_declaration", "Navigate to where a symbol is declared."),
                HelpToolInfo("get_documentation", "Get Javadoc/KDoc for a symbol."),
                HelpToolInfo("search_text", "Search text or regex across project files.")
            )
        ),
        HelpToolCategory(
            "File Operations", listOf(
                HelpToolInfo("intellij_read_file", "Read a file (optionally a line range)."),
                HelpToolInfo("intellij_write_file", "Write or edit a file (full, partial, or line-range)."),
                HelpToolInfo("create_file", "Create a new file with content."),
                HelpToolInfo("delete_file", "Delete a file from the project."),
                HelpToolInfo("list_project_files", "List project files (with optional glob pattern)."),
                HelpToolInfo("open_in_editor", "Open a file in the editor at a specific line."),
                HelpToolInfo("show_diff", "Show a diff between files or proposed changes.")
            )
        ),
        HelpToolCategory(
            "Code Quality", listOf(
                HelpToolInfo("get_problems", "Get current problems/errors from open files."),
                HelpToolInfo("get_highlights", "Get cached editor highlights (warnings, errors)."),
                HelpToolInfo("get_compilation_errors", "Fast compilation error check using cached results."),
                HelpToolInfo("run_inspections", "Run full IntelliJ inspection engine."),
                HelpToolInfo("run_qodana", "Run Qodana static analysis."),
                HelpToolInfo("run_sonarqube_analysis", "Run SonarQube for IDE analysis."),
                HelpToolInfo("apply_quickfix", "Apply an IntelliJ quickfix at a specific location."),
                HelpToolInfo("optimize_imports", "Optimize imports in a file."),
                HelpToolInfo("format_code", "Format code using IntelliJ formatter.")
            )
        ),
        HelpToolCategory(
            "Refactoring", listOf(
                HelpToolInfo("refactor (rename)", "Rename a symbol across the project."),
                HelpToolInfo("refactor (extract_method)", "Extract selected code into a new method."),
                HelpToolInfo("refactor (inline)", "Inline a method or variable."),
                HelpToolInfo("refactor (safe_delete)", "Safely delete a symbol with usage checks.")
            )
        ),
        HelpToolCategory(
            "Build, Run & Test", listOf(
                HelpToolInfo("build_project", "Trigger incremental project compilation."),
                HelpToolInfo("run_tests", "Run tests by class, method, or pattern."),
                HelpToolInfo("get_test_results", "Get results from the last test run."),
                HelpToolInfo("get_coverage", "Get code coverage data."),
                HelpToolInfo("list_tests", "List available tests."),
                HelpToolInfo("list_run_configurations", "List IntelliJ run configurations."),
                HelpToolInfo("run_configuration", "Execute a run configuration by name."),
                HelpToolInfo("run_command", "Run a shell command in the project directory.")
            )
        ),
        HelpToolCategory(
            "Git", listOf(
                HelpToolInfo("git_status", "Show working tree status."),
                HelpToolInfo("git_diff", "Show file diffs (staged or unstaged)."),
                HelpToolInfo("git_log", "Show commit history."),
                HelpToolInfo("git_blame", "Show line-by-line blame annotations."),
                HelpToolInfo("git_commit", "Create a commit (with optional auto-stage)."),
                HelpToolInfo("git_stage / git_unstage", "Stage or unstage files."),
                HelpToolInfo("git_branch", "List, create, switch, or delete branches."),
                HelpToolInfo("git_stash", "Stash or restore uncommitted changes."),
                HelpToolInfo("git_show", "Show commit details and diffs.")
            )
        ),
        HelpToolCategory(
            "Infrastructure", listOf(
                HelpToolInfo("get_project_info", "Get project structure, modules, and SDK info."),
                HelpToolInfo("get_indexing_status", "Check or wait for IDE indexing to complete."),
                HelpToolInfo("create_scratch_file", "Create a scratch file for temporary code."),
                HelpToolInfo("list_scratch_files", "List existing scratch files."),
                HelpToolInfo("download_sources", "Download library source JARs."),
                HelpToolInfo("read_ide_log", "Read recent IDE log entries."),
                HelpToolInfo("get_notifications", "Get IDE notification history."),
                HelpToolInfo("http_request", "Make HTTP requests (GET, POST, etc.).")
            )
        )
    )

    private fun addHelpRows(panel: JBPanel<*>, items: List<HelpRow?>) {
        for (item in items) {
            if (item == null) {
                panel.add(javax.swing.JSeparator(javax.swing.SwingConstants.HORIZONTAL).apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(8))
                })
            } else {
                panel.add(createHelpRow(item.icon, item.name, item.description))
            }
        }
    }

    private fun addToolCategories(panel: JBPanel<*>, categories: List<HelpToolCategory>) {
        for (category in categories) {
            panel.add(JBLabel(category.title).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0, 2, 0)
            })
            for (tool in category.tools) {
                panel.add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(24))
                    isOpaque = false
                    border = JBUI.Borders.empty(1, JBUI.scale(8), 1, 0)
                    add(JBLabel(tool.name).apply {
                        font = java.awt.Font("Monospaced", Font.PLAIN, JBUI.Fonts.label().size - 1)
                        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                        preferredSize = java.awt.Dimension(JBUI.scale(170), preferredSize.height)
                        minimumSize = preferredSize
                    }, BorderLayout.WEST)
                    add(JBLabel(tool.description).apply {
                        font = font.deriveFont(font.size2D - 1)
                    }, BorderLayout.CENTER)
                })
            }
        }
    }

    private fun addSectionHeader(panel: JBPanel<*>, title: String, large: Boolean = false) {
        panel.add(JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, JBUI.Fonts.label().size2D + if (large) 4 else 2)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })
    }

    private fun buildHelpDialogContent(): JBPanel<JBPanel<*>> {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)

            val mainPanel = JBPanel<JBPanel<*>>().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)

                addSectionHeader(this, "Agentic Copilot \u2014 Toolbar Guide", large = true)
                add(JBLabel("Each button in the toolbar, from left to right:").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })
                addHelpRows(this, buildToolbarHelpItems())

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                addSectionHeader(this, "Right Side")
                add(JBLabel("A processing timer appears while the agent is working. Next to it, a usage graph shows premium requests consumed \u2014 click it for details.").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })

                addSectionHeader(this, "Title Bar")
                for (item in buildTitleBarHelpItems()) {
                    add(createHelpRow(item.icon, item.name, item.description))
                }

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                addSectionHeader(this, "Chat Panel")
                add(JBLabel("Agent responses render as Markdown with syntax-highlighted code blocks. Tool calls appear as collapsible chips \u2014 click to expand and see arguments/results.").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                })

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                addSectionHeader(this, "Available Tools")
                add(JBLabel("The agent has access to these IDE tools via the MCP server:").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })
                addToolCategories(this, buildToolCategories())

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(16)))
                val versionText = com.github.copilot.intellij.BuildInfo.getSummary()
                add(JBLabel("Copilot Bridge $versionText").apply {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    font = font.deriveFont(font.size2D - 1)
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                })
            }

            val scrollPane = com.intellij.ui.components.JBScrollPane(mainPanel).apply {
                preferredSize = java.awt.Dimension(JBUI.scale(640), JBUI.scale(600))
                border = null
            }
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createHelpRow(icon: javax.swing.Icon, name: String, description: String): JBPanel<JBPanel<*>> {
        return JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(32))
            border = JBUI.Borders.empty(2, 0)

            add(JBLabel(icon).apply {
                preferredSize = java.awt.Dimension(JBUI.scale(20), JBUI.scale(20))
                horizontalAlignment = javax.swing.SwingConstants.CENTER
            }, BorderLayout.WEST)

            add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel(name).apply {
                    font = font.deriveFont(Font.BOLD)
                    preferredSize = java.awt.Dimension(JBUI.scale(100), preferredSize.height)
                    minimumSize = preferredSize
                }, BorderLayout.WEST)
                add(JBLabel(description), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }
}
