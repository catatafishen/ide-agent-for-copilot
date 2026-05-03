package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Standardized JSON payload sent to hook scripts on stdin.
 * All triggers receive toolName, arguments, argumentsJson, projectName, and timestamp.
 * Post and onFailure triggers additionally receive output, error, and durationMs.
 */
public record HookPayload(
    @NotNull String toolName,
    @NotNull JsonObject arguments,
    @NotNull String argumentsJson,
    @Nullable String output,
    boolean error,
    @NotNull String projectName,
    @NotNull String timestamp,
    long durationMs
) {

    /**
     * Creates a payload for pre-execution hooks (permission and pre) that don't have output yet.
     */
    public static HookPayload forPreExecution(@NotNull String toolName,
                                              @NotNull JsonObject arguments,
                                              @NotNull String projectName,
                                              @NotNull String timestamp) {
        return new HookPayload(toolName, arguments, arguments.toString(), null, false, projectName, timestamp, 0);
    }

    /**
     * Creates a payload for post-execution hooks (post and onFailure) that include tool output.
     */
    public static HookPayload forPostExecution(@NotNull String toolName,
                                               @NotNull JsonObject arguments,
                                               @Nullable String output,
                                               boolean isError,
                                               @NotNull String projectName,
                                               @NotNull String timestamp,
                                               long durationMs) {
        return new HookPayload(toolName, arguments, arguments.toString(), output, isError, projectName, timestamp, durationMs);
    }
}
