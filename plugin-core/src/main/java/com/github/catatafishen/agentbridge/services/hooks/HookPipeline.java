package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Orchestrates hook execution at each point in the MCP tool call pipeline.
 * Called from {@code McpProtocolHandler.handleToolsCall()} at the four trigger points.
 *
 * <p>Pipeline: {@code permission → pre → (tool executes) → post / onFailure}
 */
public final class HookPipeline {

    private static final Logger LOG = Logger.getInstance(HookPipeline.class);

    private HookPipeline() {
    }

    /**
     * Result of the permission hook check.
     */
    public sealed interface PermissionResult {
        record Allowed() implements PermissionResult {
        }

        record Denied(@NotNull String reason) implements PermissionResult {
        }
    }

    /**
     * Result of the pre-tool hook.
     */
    public sealed interface PreHookResult {
        record Unchanged(@NotNull JsonObject arguments) implements PreHookResult {
        }

        record Modified(@NotNull JsonObject arguments) implements PreHookResult {
        }

        record Blocked(@NotNull String error) implements PreHookResult {
        }
    }

    /**
     * Runs the permission hook for a tool. Returns allowed/denied.
     * If no hook is registered, returns allowed.
     */
    public static @NotNull PermissionResult runPermissionHook(@NotNull Project project,
                                                              @NotNull String toolName,
                                                              @NotNull JsonObject arguments)
        throws HookExecutor.HookExecutionException {

        HookDefinition hook = HookRegistry.getInstance(project).findHook(toolName, HookTrigger.PERMISSION);
        if (hook == null) return new PermissionResult.Allowed();

        HookPayload payload = HookPayload.forPreExecution(
            toolName, arguments, project.getName(), Instant.now().toString());

        HookResult result = HookExecutor.execute(hook, HookTrigger.PERMISSION, payload);
        if (result instanceof HookResult.PermissionDecision(boolean allowed, String reason) && !allowed) {
            String resolvedReason = reason != null ? reason : "Denied by hook: " + hook.id();
            LOG.info("Permission hook '" + hook.id() + "' denied tool " + toolName + ": " + resolvedReason);
            return new PermissionResult.Denied(resolvedReason);
        }
        return new PermissionResult.Allowed();
    }

    /**
     * Runs the pre-tool hook. Returns possibly modified arguments or an immediate block.
     * If no hook is registered, returns the original arguments unchanged.
     */
    public static @NotNull PreHookResult runPreHook(@NotNull Project project,
                                                    @NotNull String toolName,
                                                    @NotNull JsonObject arguments)
        throws HookExecutor.HookExecutionException {

        HookDefinition hook = HookRegistry.getInstance(project).findHook(toolName, HookTrigger.PRE);
        if (hook == null) return new PreHookResult.Unchanged(arguments);

        HookPayload payload = HookPayload.forPreExecution(
            toolName, arguments, project.getName(), Instant.now().toString());

        HookResult result = HookExecutor.execute(hook, HookTrigger.PRE, payload);
        if (result instanceof HookResult.PreHookFailure(String error)) {
            LOG.info("Pre-hook '" + hook.id() + "' blocked tool " + toolName + ": " + error);
            return new PreHookResult.Blocked(error);
        }
        if (result instanceof HookResult.ModifiedArguments(JsonObject modifiedArguments)) {
            LOG.info("Pre-hook '" + hook.id() + "' modified arguments for " + toolName);
            return new PreHookResult.Modified(modifiedArguments);
        }
        return new PreHookResult.Unchanged(arguments);
    }

    /**
     * Runs the post-tool hook. Returns possibly modified output.
     * If no hook is registered, returns the original output unchanged.
     */
    public static @Nullable String runPostHook(@NotNull Project project,
                                               @NotNull String toolName,
                                               @NotNull JsonObject arguments,
                                               @Nullable String output,
                                               long durationMs)
        throws HookExecutor.HookExecutionException {

        HookDefinition hook = HookRegistry.getInstance(project).findHook(toolName, HookTrigger.POST);
        if (hook == null) return output;

        HookPayload payload = HookPayload.forPostExecution(
            toolName, arguments, output, false, project.getName(), Instant.now().toString(), durationMs);

        HookResult result = HookExecutor.execute(hook, HookTrigger.POST, payload);
        return applyOutputResult(result, output);
    }

    /**
     * Runs the onFailure hook. Returns possibly modified error message.
     * If no hook is registered, returns the original error unchanged.
     */
    public static @NotNull String runFailureHook(@NotNull Project project,
                                                 @NotNull String toolName,
                                                 @NotNull JsonObject arguments,
                                                 @NotNull String errorMessage,
                                                 long durationMs)
        throws HookExecutor.HookExecutionException {

        HookDefinition hook = HookRegistry.getInstance(project).findHook(toolName, HookTrigger.ON_FAILURE);
        if (hook == null) return errorMessage;

        HookPayload payload = HookPayload.forPostExecution(
            toolName, arguments, errorMessage, true, project.getName(), Instant.now().toString(), durationMs);

        HookResult result = HookExecutor.execute(hook, HookTrigger.ON_FAILURE, payload);
        String modified = applyOutputResult(result, errorMessage);
        return modified != null ? modified : errorMessage;
    }

    private static @Nullable String applyOutputResult(@NotNull HookResult result, @Nullable String original) {
        if (result instanceof HookResult.OutputModification mod) {
            if (mod.isReplacement()) {
                return mod.replacedOutput();
            }
            if (mod.appendedText() != null) {
                String base = original != null ? original : "";
                return base + mod.appendedText();
            }
        }
        return original;
    }
}
