package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Result of classifying an ACP terminal command for MCP redirection.
 *
 * @param toolName    MCP tool to invoke
 * @param args        arguments for the MCP tool
 * @param postProcess transformation applied to the raw MCP result before it is
 *                    handed back to the agent (e.g. stripping IntelliJ-specific
 *                    headers so the output looks more like the original CLI tool)
 * @param exitCodeFor computes the exit code reported to the agent based on the
 *                    processed output. Allows {@code grep}/{@code rg} to return
 *                    {@code 1} when there are no matches, matching POSIX semantics.
 *                    Only consulted on success — MCP errors always force exit
 *                    code {@code 1} regardless.
 */
public record RedirectPlan(
    @NotNull String toolName,
    @NotNull JsonObject args,
    @NotNull UnaryOperator<String> postProcess,
    @NotNull ToIntFunction<String> exitCodeFor
) {

    /**
     * Convenience for the common case: pass the MCP result through unchanged with
     * exit code {@code 0} on success.
     */
    public static @NotNull RedirectPlan of(@NotNull String tool, @NotNull JsonObject args) {
        return new RedirectPlan(tool, args, UnaryOperator.identity(), output -> 0);
    }
}
