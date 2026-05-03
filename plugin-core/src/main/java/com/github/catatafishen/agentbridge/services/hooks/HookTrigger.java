package com.github.catatafishen.agentbridge.services.hooks;

/**
 * Defines the four trigger points in the MCP tool execution pipeline where hooks can run.
 *
 * <p>Execution order: {@code PERMISSION → PRE → (tool executes) → POST / ON_FAILURE}
 */
public enum HookTrigger {

    /**
     * Runs before execution. A policy gate that returns allow/deny.
     * Separate from PRE because it's a binary decision, not argument enrichment.
     */
    PERMISSION("permission"),

    /**
     * Runs before execution, after permission. Can modify tool arguments.
     */
    PRE("pre"),

    /**
     * Runs after successful execution. Can modify or append to tool output.
     */
    POST("post"),

    /**
     * Runs after failed execution. Can modify or append to the error message.
     */
    ON_FAILURE("onFailure");

    private final String jsonKey;

    HookTrigger(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /**
     * The key used in hook.json's {@code "hooks"} object (e.g. {@code "permission"}, {@code "onFailure"}).
     */
    public String jsonKey() {
        return jsonKey;
    }

    /**
     * Resolves a trigger from its JSON key. Throws if the key is not recognized.
     */
    public static HookTrigger fromJsonKey(String key) {
        for (HookTrigger trigger : values()) {
            if (trigger.jsonKey.equals(key)) return trigger;
        }
        throw new IllegalArgumentException("Unknown hook trigger: " + key);
    }
}
