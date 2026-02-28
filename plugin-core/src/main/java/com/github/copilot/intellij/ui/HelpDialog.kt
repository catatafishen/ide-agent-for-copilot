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
 * Help dialog action showing toolbar guide and plugin info.
 * Extracted from AgenticCopilotToolWindowContent for file-size reduction.
 */
internal class HelpAction(private val project: Project) : AnAction(
    "Help", "Show help for all toolbar features and plugin behavior",
    com.intellij.icons.AllIcons.Actions.Help
) {
    private class HelpRow(val icon: javax.swing.Icon, val name: String, val description: String)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val content = buildHelpDialogContent()
        com.intellij.openapi.ui.DialogBuilder(project).apply {
            setTitle("IDE Agent for Copilot \u2014 Help")
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
            "Dropdown: choose the AI model. Premium models show a cost multiplier (e.g. \"3\u00d7\")."
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
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Nodes.Folder,
            "Project Files",
            "Dropdown: open Instructions, TODO, Agent Definitions, or MCP Server Instructions."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.Actions.Restart,
            "Restart \u25be",
            "Dropdown: \u201cRestart (keep history)\u201d or \u201cClear and restart\u201d."
        ),
        null,
        HelpRow(
            com.intellij.icons.AllIcons.ToolbarDecorator.Export,
            "Export Chat",
            "Copy the full conversation to clipboard (as text or HTML)."
        ),
        HelpRow(
            com.intellij.icons.AllIcons.Actions.ListFiles,
            "Tool Permissions",
            "Open the tool permissions panel to review which tools the agent can use."
        ),
        HelpRow(com.intellij.icons.AllIcons.Actions.Help, "Help", "This dialog."),
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

                addSectionHeader(this, "IDE Agent for Copilot \u2014 Toolbar Guide", large = true)
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

                add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
                addSectionHeader(this, "Chat Panel")
                add(JBLabel("Agent responses render as Markdown with syntax-highlighted code blocks. Tool calls appear as collapsible chips \u2014 click to expand and see arguments/results.").apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                })
            }

            val scrollPane = com.intellij.ui.components.JBScrollPane(mainPanel).apply {
                preferredSize = java.awt.Dimension(JBUI.scale(580), JBUI.scale(480))
                border = null
            }
            add(scrollPane, BorderLayout.CENTER)

            // Sticky footer: always-visible version/build info
            val versionText = com.github.copilot.intellij.BuildInfo.getSummary()
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBUI.CurrentTheme.Popup.separatorColor(), 1, 0, 0, 0),
                    JBUI.Borders.empty(6, 0, 0, 0)
                )
                isOpaque = false
                add(JBLabel("IDE Agent for Copilot $versionText").apply {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    font = font.deriveFont(font.size2D - 1)
                }, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
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
                    preferredSize = java.awt.Dimension(JBUI.scale(120), preferredSize.height)
                    minimumSize = preferredSize
                }, BorderLayout.WEST)
                add(JBLabel(description), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }
}
