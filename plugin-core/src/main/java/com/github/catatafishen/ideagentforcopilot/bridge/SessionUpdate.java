package com.github.catatafishen.ideagentforcopilot.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Sealed type hierarchy for structured agent → UI update events.
 *
 * <p>Replaces the previous ad-hoc {@link com.google.gson.JsonObject} payloads, giving each
 * event a well-typed shape that the UI can pattern-match exhaustively.  The concrete type
 * always correlates to a {@link AgentClient.SessionUpdateType} enum value.</p>
 */
public sealed interface SessionUpdate
        permits SessionUpdate.ToolCall,
                SessionUpdate.ToolCallUpdate,
                SessionUpdate.AgentThought,
                SessionUpdate.TurnUsage,
                SessionUpdate.Banner,
                SessionUpdate.Plan {

    /**
     * A new tool call has started.
     *
     * @param toolCallId unique ID used to correlate with the matching {@link ToolCallUpdate}
     * @param title      normalised tool name (MCP prefix stripped)
     * @param kind       tool kind: {@code "read"}, {@code "edit"}, {@code "execute"}, {@code "search"}, or {@code "other"}
     * @param arguments  serialised JSON string of the tool arguments, or {@code null} if empty
     * @param filePaths  file paths extracted from the tool event (may be empty)
     */
    record ToolCall(
            @NotNull String toolCallId,
            @NotNull String title,
            @NotNull String kind,
            @Nullable String arguments,
            @NotNull List<String> filePaths
    ) implements SessionUpdate {}

    /**
     * A tool call has completed or failed.
     *
     * @param toolCallId ID matching the originating {@link ToolCall}
     * @param status     {@code "completed"} or {@code "failed"}
     * @param result     result text for a completed call (may be {@code null})
     * @param error      error message for a failed call (may be {@code null})
     */
    record ToolCallUpdate(
            @NotNull String toolCallId,
            @NotNull String status,
            @Nullable String result,
            @Nullable String error
    ) implements SessionUpdate {}

    /**
     * A reasoning/thinking chunk from the model.
     *
     * @param text the thinking text fragment
     */
    record AgentThought(@NotNull String text) implements SessionUpdate {}

    /**
     * Turn-level token and cost statistics, emitted once per completed turn.
     *
     * @param inputTokens  total input tokens consumed
     * @param outputTokens total output tokens generated
     * @param costUsd      estimated cost in USD
     */
    record TurnUsage(int inputTokens, int outputTokens, double costUsd) implements SessionUpdate {}

    /**
     * An agent-initiated banner notification.
     *
     * @param message  human-readable text to display
     * @param level    {@code "warning"} (yellow) or {@code "error"} (red)
     * @param clearOn  {@code "next_success"} to re-show until a successful turn, or
     *                 {@code "manual"} to show once (default)
     */
    record Banner(
            @NotNull String message,
            @NotNull String level,
            @NotNull String clearOn
    ) implements SessionUpdate {}

    /**
     * A plan update from the ACP agent, carrying a list of plan entries.
     *
     * @param entries the current plan entry list
     */
    record Plan(@NotNull List<PlanEntry> entries) implements SessionUpdate {

        /**
         * A single entry in the agent plan.
         *
         * @param content  description of the step
         * @param status   step status (e.g. {@code "pending"}, {@code "in_progress"}, {@code "done"})
         * @param priority optional priority string, empty if not set
         */
        public record PlanEntry(
                @NotNull String content,
                @NotNull String status,
                @NotNull String priority
        ) {}
    }
}
