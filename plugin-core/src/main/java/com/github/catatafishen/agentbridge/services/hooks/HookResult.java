package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parsed result from a hook script's stdout. Each trigger type produces a specific
 * subclass that captures its semantics.
 */
public sealed interface HookResult {

    /**
     * Hook produced no output — no modification to tool behavior.
     */
    record NoOp() implements HookResult {
    }

    /**
     * Permission hook decision: allow or deny the tool call.
     */
    record PermissionDecision(boolean allowed, @Nullable String reason) implements HookResult {
    }

    /**
     * Pre-hook result: optionally modified arguments.
     */
    record ModifiedArguments(@NotNull JsonObject arguments) implements HookResult {
    }

    /**
     * Pre-hook result: stop execution immediately with an error.
     */
    record PreHookFailure(@NotNull String error) implements HookResult {
    }

    record OutputModification(@Nullable String replacedOutput,
                              @Nullable String appendedText,
                              @Nullable Boolean stateOverride) implements HookResult {

        /**
         * True if this result replaces the entire output (as opposed to appending).
         */
        public boolean isReplacement() {
            return replacedOutput != null;
        }

        /**
         * Convenience constructor for output modifications without state change.
         */
        public OutputModification(@Nullable String replacedOutput, @Nullable String appendedText) {
            this(replacedOutput, appendedText, null);
        }
    }
}
