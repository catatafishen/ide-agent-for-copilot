package com.github.catatafishen.ideagentforcopilot.session.exporters;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Shared utilities for session exporters.
 */
public final class ExporterUtil {

    private static final int MAX_TOOL_NAME_LENGTH = 200;
    private static final Pattern INVALID_TOOL_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_{3,}");

    private ExporterUtil() {
    }

    /**
     * Sanitizes a tool name for APIs that require names to match {@code [a-zA-Z0-9_-]+}
     * (OpenAI Responses API, Anthropic API, etc.).
     *
     * <p>Session data stores human-readable titles for tool calls (e.g., "git add src/Foo.java",
     * "Check for public console APIs") which contain spaces and other invalid characters.
     * This method replaces invalid characters with underscores, collapses runs of 3+
     * underscores (preserving the {@code __} MCP separator convention), and truncates to fit.</p>
     */
    public static String sanitizeToolName(@NotNull String rawName) {
        if (rawName.isEmpty()) return "unknown_tool";
        String sanitized = INVALID_TOOL_NAME_CHARS.matcher(rawName).replaceAll("_");
        sanitized = CONSECUTIVE_UNDERSCORES.matcher(sanitized).replaceAll("__");
        if (sanitized.startsWith("_")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("_")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.length() > MAX_TOOL_NAME_LENGTH) sanitized = sanitized.substring(0, MAX_TOOL_NAME_LENGTH);
        return sanitized.isEmpty() ? "unknown_tool" : sanitized;
    }
}
