package com.github.copilot.intellij.psi;

import com.google.gson.JsonObject;

/**
 * Functional interface for PSI Bridge tool handlers.
 * Each tool implementation takes a JSON arguments object and returns a string result.
 */
@FunctionalInterface
interface ToolHandler {
    @SuppressWarnings("RedundantThrows")
        // handlers may throw checked exceptions
    String handle(JsonObject args) throws Exception;
}
