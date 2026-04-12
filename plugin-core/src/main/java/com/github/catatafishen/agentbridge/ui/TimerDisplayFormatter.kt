package com.github.catatafishen.agentbridge.ui

/**
 * Pure formatting functions for timer and stats display in [ProcessingTimerPanel].
 * Extracted to enable unit testing without Swing dependencies.
 */
object TimerDisplayFormatter {

    /**
     * Formats elapsed seconds as "Xs" (under a minute) or "Xm Ys" (a minute or more).
     */
    fun formatElapsedTime(elapsedSeconds: Long): String =
        if (elapsedSeconds < 60) "${elapsedSeconds}s"
        else "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"

    /**
     * Formats a line-added count for display: "+N" when positive, empty otherwise.
     */
    fun formatLinesAdded(count: Int): String =
        if (count > 0) "+$count" else ""

    /**
     * Formats a line-removed count for display: "-N" when positive, empty otherwise.
     */
    fun formatLinesRemoved(count: Int): String =
        if (count > 0) "-$count" else ""

    /**
     * Formats a tool-call count with a bullet prefix: "• N tools" when positive, empty otherwise.
     */
    fun formatToolCount(count: Int): String =
        if (count > 0) "\u2022 $count tools" else ""

    /**
     * Returns true when the turn has completed and produced displayable usage data
     * (tokens or cost).
     */
    fun hasDisplayableUsage(
        isRunning: Boolean,
        costUsd: Double?,
        inputTokens: Int,
        outputTokens: Int,
    ): Boolean = !isRunning && ((costUsd?.let { it > 0.0 } ?: false) || (inputTokens + outputTokens) > 0)
}
