package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 {@link SessionMessage} list into Copilot CLI's native {@code events.jsonl} format.
 *
 * <p>The event types mirror what {@code CopilotClientImporter} reads:
 * {@code session.start}, {@code user.message}, {@code assistant.reasoning},
 * {@code assistant.message} (with {@code toolRequests}), {@code tool.execution_complete},
 * {@code assistant.turn_end}.</p>
 */
public final class CopilotClientExporter {

    private static final Logger LOG = Logger.getInstance(CopilotClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String CONTENT_KEY = "content";
    private static final String TOOL_CALL_ID_KEY = "toolCallId";
    private static final String RESULT_KEY = "result";

    private CopilotClientExporter() {
    }

    @NotNull
    public static Path defaultEventsPath(@NotNull String basePath) {
        return Path.of(basePath, ".agent-work", "copilot", "session-state", "events.jsonl");
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath) throws IOException {

        StringBuilder sb = new StringBuilder();

        String model = findFirstModel(messages);
        sb.append(sessionStartEvent(model)).append('\n');

        for (SessionMessage msg : messages) {
            switch (msg.role) {
                case "user" -> writeUserMessage(msg, sb);
                case "assistant" -> writeAssistantTurn(msg, sb);
                default -> { /* separators and unknown roles are skipped */ }
            }
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported v2 session to Copilot events.jsonl: " + targetPath);
    }

    @NotNull
    private static String sessionStartEvent(@Nullable String model) {
        JsonObject data = new JsonObject();
        if (model != null) {
            data.addProperty("selectedModel", model);
        }
        return eventLine("session.start", data);
    }

    private static void writeUserMessage(@NotNull SessionMessage msg, @NotNull StringBuilder sb) {
        String text = extractText(msg);
        if (text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        sb.append(eventLine("user.message", data)).append('\n');
    }

    private static void writeAssistantTurn(@NotNull SessionMessage msg, @NotNull StringBuilder sb) {
        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, "type");
            if (type == null) continue;

            switch (type) {
                case "reasoning" -> writeReasoningEvent(part, sb);
                case "text" -> writeAssistantMessageEvent(part, msg, sb);
                case "tool-invocation" -> writeToolEvents(part, sb);
                case "subagent" -> writeSubagentEvents(part, sb);
                default -> { /* status, file, etc. — not representable in events.jsonl */ }
            }
        }

        sb.append(eventLine("assistant.turn_end", new JsonObject())).append('\n');
    }

    private static void writeReasoningEvent(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        sb.append(eventLine("assistant.reasoning", data)).append('\n');
    }

    private static void writeAssistantMessageEvent(
        @NotNull JsonObject part,
        @NotNull SessionMessage msg,
        @NotNull StringBuilder sb) {
        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        if (msg.model != null) {
            data.addProperty("model", msg.model);
        }
        sb.append(eventLine("assistant.message", data)).append('\n');
    }

    private static void writeToolEvents(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        JsonObject invocation = part.getAsJsonObject("toolInvocation");
        if (invocation == null) return;

        String state = JsonlUtil.getStr(invocation, "state");
        String toolCallId = JsonlUtil.getStr(invocation, TOOL_CALL_ID_KEY);
        if (toolCallId == null) toolCallId = UUID.randomUUID().toString();
        String toolName = JsonlUtil.getStr(invocation, "toolName");
        if (toolName == null) toolName = "unknown";
        String argsStr = JsonlUtil.getStr(invocation, "args");

        if ("call".equals(state) || RESULT_KEY.equals(state)) {
            JsonObject toolReq = new JsonObject();
            toolReq.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            toolReq.addProperty("name", toolName);
            if (argsStr != null) {
                try {
                    toolReq.add("arguments", JsonParser.parseString(argsStr));
                } catch (Exception e) {
                    toolReq.addProperty("arguments", argsStr);
                }
            }
            JsonArray toolRequests = new JsonArray();
            toolRequests.add(toolReq);

            JsonObject data = new JsonObject();
            data.addProperty(CONTENT_KEY, "");
            data.add("toolRequests", toolRequests);
            sb.append(eventLine("assistant.message", data)).append('\n');
        }

        if (RESULT_KEY.equals(state)) {
            String result = JsonlUtil.getStr(invocation, RESULT_KEY);
            JsonObject resultObj = new JsonObject();
            resultObj.addProperty(CONTENT_KEY, result != null ? result : "");

            JsonObject data = new JsonObject();
            data.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            data.add(RESULT_KEY, resultObj);
            sb.append(eventLine("tool.execution_complete", data)).append('\n');
        }
    }

    private static void writeSubagentEvents(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        String agentType = JsonlUtil.getStr(part, "agentType");
        String description = JsonlUtil.getStr(part, "description");
        String status = JsonlUtil.getStr(part, "status");
        String toolCallId = UUID.randomUUID().toString();

        JsonObject startData = new JsonObject();
        startData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
        startData.addProperty("agentName", agentType != null ? agentType : "general-purpose");
        startData.addProperty("agentDisplayName", description != null ? description : "");
        sb.append(eventLine("subagent.started", startData)).append('\n');

        if ("done".equals(status)) {
            JsonObject completeData = new JsonObject();
            completeData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            sb.append(eventLine("subagent.completed", completeData)).append('\n');
        }
    }

    @NotNull
    private static String extractText(@NotNull SessionMessage msg) {
        StringBuilder textBuilder = new StringBuilder();
        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, "type");
            if ("text".equals(type)) {
                String text = JsonlUtil.getStr(part, "text");
                if (text != null) textBuilder.append(text);
            }
        }
        return textBuilder.toString();
    }

    @Nullable
    private static String findFirstModel(@NotNull List<SessionMessage> messages) {
        for (SessionMessage msg : messages) {
            if (msg.model != null && !msg.model.isEmpty()) return msg.model;
        }
        return null;
    }

    @NotNull
    private static String eventLine(@NotNull String type, @NotNull JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.add("data", data);
        return GSON.toJson(event);
    }
}
