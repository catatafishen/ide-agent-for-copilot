package com.github.catatafishen.agentbridge.ui

/**
 * Immutable snapshot of session-level statistics, produced by [ProcessingTimerPanel]
 * on every data change. Consumed by the side panel's Session tab to render labeled
 * stat rows without coupling to Swing internals.
 */
data class SessionStatsSnapshot(
    val isRunning: Boolean,
    val turnElapsedSec: Long,
    val sessionTotalTimeSec: Long,
    val sessionTurnCount: Int,
    val sessionToolCalls: Int,
    val sessionLinesAdded: Int,
    val sessionLinesRemoved: Int,
    val sessionInputTokens: Long,
    val sessionOutputTokens: Long,
    val sessionCostUsd: Double,
    /** True when the agent uses premium-request multipliers (Copilot) rather than raw tokens. */
    val multiplierMode: Boolean,
)
