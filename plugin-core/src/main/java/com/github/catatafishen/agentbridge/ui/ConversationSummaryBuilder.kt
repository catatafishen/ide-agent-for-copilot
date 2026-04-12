package com.github.catatafishen.agentbridge.ui

/**
 * Pure utility for building compressed conversation summaries.
 *
 * All functions are stateless data transformations over [EntryData] —
 * no UIUtil, theme colors, or other UI dependencies.
 *
 * Extracted from [ConversationExporter] to make the summary logic
 * independently testable and reusable.
 */
internal object ConversationSummaryBuilder {

    /**
     * Intermediate representation of a single user→agent turn,
     * produced by [groupIntoTurns] and consumed by [formatTurnForSummary].
     */
    data class TurnData(
        val id: String,
        val userText: String,
        val agentText: String,
        val toolCallCount: Int,
        val thinkingCount: Int,
        val subAgentCount: Int,
    )

    /**
     * Group a flat list of [EntryData] into named turns (t1, t2, …).
     *
     * A new turn starts at every [EntryData.Prompt]. Agent text, tool calls,
     * thinking blocks, and sub-agent invocations that follow are accumulated
     * into the same turn until the next prompt arrives.
     */
    fun groupIntoTurns(entries: List<EntryData>): List<TurnData> {
        val turns = mutableListOf<TurnData>()
        var currentPrompt: EntryData.Prompt? = null
        val agentTextBuf = StringBuilder()
        var toolCount = 0
        var thinkCount = 0
        var subAgentCount = 0

        fun flush() {
            val p = currentPrompt ?: return
            turns.add(
                TurnData(
                    id = "t${turns.size + 1}",
                    userText = p.text,
                    agentText = agentTextBuf.toString().trim(),
                    toolCallCount = toolCount,
                    thinkingCount = thinkCount,
                    subAgentCount = subAgentCount,
                )
            )
        }

        for (e in entries) {
            when (e) {
                is EntryData.Prompt -> {
                    flush()
                    currentPrompt = e
                    agentTextBuf.clear()
                    toolCount = 0
                    thinkCount = 0
                    subAgentCount = 0
                }

                is EntryData.Text -> agentTextBuf.append(e.raw)
                is EntryData.ToolCall -> toolCount++
                is EntryData.Thinking -> thinkCount++
                is EntryData.SubAgent -> subAgentCount++
                is EntryData.ContextFiles,
                is EntryData.SessionSeparator,
                is EntryData.Status,
                is EntryData.Nudge,
                is EntryData.TurnStats -> { /* not relevant for turn grouping */
                }
            }
        }
        flush()
        return turns
    }

    /**
     * Format a single [TurnData] for inclusion in a compressed summary.
     *
     * @param turn the turn to format
     * @param full if `true` the full text is included; otherwise it is truncated to 500 chars
     */
    fun formatTurnForSummary(turn: TurnData, full: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("[${turn.id}] User: ${truncateField(turn.userText, full, "use turn_id='${turn.id}' for full")}")
        if (turn.agentText.isNotEmpty()) {
            sb.appendLine("Agent: ${truncateField(turn.agentText, full, "truncated")}")
        }
        val markerLine = buildMarkerLine(turn)
        if (markerLine != null) sb.appendLine(markerLine)
        return sb.toString()
    }

    /**
     * Truncate [text] to 500 characters unless [full] is `true`,
     * appending a `…[hint]` suffix when truncated.
     */
    fun truncateField(text: String, full: Boolean, hint: String): String =
        if (full || text.length <= 500) text else text.take(500) + "…[$hint]"

    /**
     * Build a count-marker line such as `[5 tool calls, 2 thoughts]`.
     * Returns `null` when there are no markers to show.
     */
    fun buildMarkerLine(turn: TurnData): String? {
        val markers = buildList {
            if (turn.toolCallCount > 0) add("${turn.toolCallCount} tool call${if (turn.toolCallCount > 1) "s" else ""}")
            if (turn.thinkingCount > 0) add("${turn.thinkingCount} thinking block${if (turn.thinkingCount > 1) "s" else ""}")
            if (turn.subAgentCount > 0) add("${turn.subAgentCount} sub-agent${if (turn.subAgentCount > 1) "s" else ""}")
        }
        return if (markers.isNotEmpty()) "[${markers.joinToString(", ")}]" else null
    }
}
