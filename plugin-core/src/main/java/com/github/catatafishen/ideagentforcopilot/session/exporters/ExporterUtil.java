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
    private static final String AGENTBRIDGE_DASH = "agentbridge-";
    private static final String AGENTBRIDGE_UNDERSCORE = "agentbridge_";
    private static final String AGENTBRIDGE_KIRO = "@agentbridge/";

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

    /**
     * Normalizes a tool name for Codex rollout export by ensuring it starts with
     * {@code agentbridge_}.
     *
     * <p><b>Why extracted:</b> Codex presents MCP tools to the model with the server name
     * as a prefix (e.g., {@code agentbridge_read_file}). The exported rollout must use the
     * same names so the model recognizes them as the same tools after session restore.
     * Different clients use different prefix conventions:</p>
     * <ul>
     *   <li>Copilot: {@code agentbridge-read_file} (dash separator)</li>
     *   <li>Codex/OpenCode: {@code agentbridge_read_file} (underscore separator)</li>
     *   <li>Kiro: {@code @agentbridge/read_file} (at-sign + slash)</li>
     *   <li>Claude: {@code read_file} (no prefix)</li>
     * </ul>
     *
     * <p>This method strips any existing prefix and adds the canonical
     * {@code agentbridge_} prefix that Codex expects.</p>
     */
    @NotNull
    public static String normalizeToolNameForCodex(@NotNull String rawName) {
        String base = rawName;
        if (base.startsWith(AGENTBRIDGE_DASH)) {
            base = base.substring(AGENTBRIDGE_DASH.length());
        } else if (base.startsWith(AGENTBRIDGE_UNDERSCORE)) {
            base = base.substring(AGENTBRIDGE_UNDERSCORE.length());
        } else if (base.startsWith(AGENTBRIDGE_KIRO)) {
            base = base.substring(AGENTBRIDGE_KIRO.length());
        }
        String sanitized = sanitizeToolName(base);
        return AGENTBRIDGE_UNDERSCORE + sanitized;
    }
}
