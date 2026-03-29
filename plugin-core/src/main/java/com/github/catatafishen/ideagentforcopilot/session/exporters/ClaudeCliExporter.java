package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 {@link SessionMessage} list into Claude CLI's native session JSONL format.
 *
 * <p>Claude CLI stores sessions as event lines — NOT as bare Anthropic API messages.
 * Each line is a JSON object with {@code type}, {@code uuid}, {@code parentUuid},
 * {@code message} (the Anthropic payload), {@code timestamp}, {@code sessionId}, etc.
 * This is fundamentally different from the Anthropic API format that
 * {@link AnthropicClientExporter} writes (which is correct for Kiro/Junie).</p>
 */
public final class ClaudeCliExporter {

    private static final Logger LOG = Logger.getInstance(ClaudeCliExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_MAX_TOKEN_ESTIMATE = 20_000;
    private static final String FIELD_SESSION_ID = "sessionId";

    private ClaudeCliExporter() {
    }

    /**
     * Exports v2 messages to a Claude CLI session JSONL file.
     *
     * @param sessionId the UUID that identifies this Claude session
     * @param cwd       the project working directory
     */
    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        @NotNull String sessionId,
        @NotNull String cwd) throws IOException {

        List<SessionMessage> budgeted = AnthropicClientExporter.applyTokenBudget(
            messages, DEFAULT_MAX_TOKEN_ESTIMATE);
        List<AnthropicMessage> anthropicMessages = AnthropicClientExporter.toAnthropicMessages(budgeted);
        anthropicMessages = AnthropicClientExporter.ensureUserFirst(anthropicMessages);

        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now();
        String parentUuid = null;

        sb.append(queueEvent("enqueue", sessionId, now)).append('\n');
        sb.append(queueEvent("dequeue", sessionId, now)).append('\n');

        for (AnthropicMessage msg : anthropicMessages) {
            String uuid = UUID.randomUUID().toString();
            sb.append(messageEvent(msg, uuid, parentUuid, sessionId, cwd, now)).append('\n');
            parentUuid = uuid;
        }

        // Append last-prompt entry so Claude CLI knows the conversation head.
        // Without this, Claude CLI creates a synthetic assistant branch from the first user message
        // when resuming via --resume, causing it to ignore the exported conversation history.
        // With last-prompt, Claude correctly resumes from after the last assistant response.
        String lastUserPromptText = extractLastUserPromptText(anthropicMessages);
        if (!lastUserPromptText.isEmpty()) {
            JsonObject lastPromptEvent = new JsonObject();
            lastPromptEvent.addProperty("type", "last-prompt");
            lastPromptEvent.addProperty("lastPrompt", lastUserPromptText);
            lastPromptEvent.addProperty(FIELD_SESSION_ID, sessionId);
            sb.append(GSON.toJson(lastPromptEvent)).append('\n');
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported v2 session to Claude CLI format: " + targetPath);
    }

    /**
     * Extracts the text content from the last user message in the list that contains
     * text blocks (skipping tool_result-only user messages). Used to populate the
     * {@code last-prompt} entry in the exported Claude CLI session file.
     */
    @NotNull
    private static String extractLastUserPromptText(@NotNull List<AnthropicMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AnthropicMessage msg = messages.get(i);
            if (!"user".equals(msg.role)) continue;
            StringBuilder sb = new StringBuilder();
            for (JsonObject block : msg.contentBlocks) {
                if (block.has("text")) {
                    sb.append(block.get("text").getAsString());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return "";
    }

    @NotNull
    private static String queueEvent(
        @NotNull String operation,
        @NotNull String sessionId,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();
        event.addProperty("type", "queue-operation");
        event.addProperty("operation", operation);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty(FIELD_SESSION_ID, sessionId);
        return GSON.toJson(event);
    }

    @NotNull
    private static String messageEvent(
        @NotNull AnthropicMessage msg,
        @NotNull String uuid,
        @Nullable String parentUuid,
        @NotNull String sessionId,
        @NotNull String cwd,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();

        if ("user".equals(msg.role)) {
            event.addProperty("type", "user");
            event.addProperty("promptId", UUID.randomUUID().toString());
            event.addProperty("permissionMode", "bypassPermissions");
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
        } else {
            event.addProperty("type", "assistant");
            event.addProperty("requestId", UUID.randomUUID().toString());
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
        }

        if (parentUuid != null) {
            event.addProperty("parentUuid", parentUuid);
        } else {
            event.add("parentUuid", null);
        }
        event.addProperty("isSidechain", false);

        JsonObject messagePayload = new JsonObject();
        messagePayload.addProperty("role", msg.role);
        JsonArray contentArray = new JsonArray();
        msg.contentBlocks.forEach(contentArray::add);
        messagePayload.add("content", contentArray);
        event.add("message", messagePayload);

        event.addProperty("uuid", uuid);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty("cwd", cwd);
        event.addProperty(FIELD_SESSION_ID, sessionId);
        event.addProperty("version", "1");

        return GSON.toJson(event);
    }
}
