package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 session messages to Codex format: a rollout JSONL file (OpenAI Responses API)
 * and an entry in the Codex SQLite database ({@code ~/.codex/codex.db}).
 * <p>
 * The exported session can be resumed via {@code codex resume <session-id>} or
 * via the {@code thread/resume} JSON-RPC request.
 */
public final class CodexExporter {

    private static final Logger LOG = Logger.getInstance(CodexExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CodexExporter() {}

    /**
     * Returns the default Codex sessions directory.
     */
    @NotNull
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".codex", "sessions");
    }

    /**
     * Returns the default Codex database path.
     */
    @NotNull
    public static Path defaultDbPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "codex.db");
    }

    /**
     * Exports v2 session messages to a new Codex session.
     *
     * @param messages    v2 session messages to export
     * @param sessionsDir Codex sessions directory (typically {@code ~/.codex/sessions/})
     * @param dbPath      path to codex.db (typically {@code ~/.codex/codex.db})
     * @return the new thread ID, or null if export failed
     */
    @Nullable
    public static String exportSession(
            @NotNull List<SessionMessage> messages,
            @NotNull Path sessionsDir,
            @NotNull Path dbPath) {
        if (messages.isEmpty()) return null;

        try {
            String threadId = UUID.randomUUID().toString();
            Path sessionDir = sessionsDir.resolve(threadId);
            Files.createDirectories(sessionDir);

            // Write rollout JSONL
            Path rolloutFile = sessionDir.resolve("rollout.jsonl");
            writeRolloutFile(messages, rolloutFile);

            // Insert into codex.db if it exists
            if (Files.exists(dbPath)) {
                insertThread(dbPath, threadId, rolloutFile.toString());
            }

            LOG.info("Exported v2 session to Codex: " + threadId);
            return threadId;
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Codex", e);
            return null;
        }
    }

    /**
     * Writes v2 session messages as a Codex rollout JSONL file in OpenAI Responses API format.
     */
    static void writeRolloutFile(@NotNull List<SessionMessage> messages, @NotNull Path rolloutFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) continue;
            convertMessageToRolloutItems(msg, sb);
        }
        Files.writeString(rolloutFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Converts a single v2 SessionMessage into one or more Codex rollout JSONL lines.
     */
    private static void convertMessageToRolloutItems(@NotNull SessionMessage msg, @NotNull StringBuilder sb) {
        if ("user".equals(msg.role)) {
            convertUserMessage(msg, sb);
        } else if ("assistant".equals(msg.role)) {
            convertAssistantMessage(msg, sb);
        }
    }

    private static void convertUserMessage(@NotNull SessionMessage msg, @NotNull StringBuilder sb) {
        // Collect all text parts into a single user message
        StringBuilder textBuilder = new StringBuilder();
        for (JsonObject part : msg.parts) {
            String type = getStr(part, "type");
            if ("text".equals(type)) {
                String text = getStr(part, "text");
                if (text != null) textBuilder.append(text);
            }
        }

        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject inputText = new JsonObject();
        inputText.addProperty("type", "input_text");
        inputText.addProperty("text", textBuilder.toString());
        content.add(inputText);
        item.add("content", content);
        sb.append(GSON.toJson(item)).append('\n');
    }

    private static void convertAssistantMessage(@NotNull SessionMessage msg, @NotNull StringBuilder sb) {
        for (JsonObject part : msg.parts) {
            String type = getStr(part, "type");
            if (type == null) continue;

            switch (type) {
                case "text" -> writeAssistantTextItem(part, sb);
                case "reasoning" -> writeReasoningItem(part, sb);
                case "tool-invocation" -> writeToolItems(part, sb);
                // subagent, status, file — skip (no Codex equivalent)
                default -> { /* skip */ }
            }
        }
    }

    private static void writeAssistantTextItem(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        String text = getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", "assistant");
        item.addProperty("id", "resp_" + UUID.randomUUID().toString().substring(0, 8));
        JsonArray content = new JsonArray();
        JsonObject outputText = new JsonObject();
        outputText.addProperty("type", "output_text");
        outputText.addProperty("text", text);
        content.add(outputText);
        item.add("content", content);
        sb.append(GSON.toJson(item)).append('\n');
    }

    private static void writeReasoningItem(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        String text = getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject item = new JsonObject();
        item.addProperty("type", "reasoning");
        item.addProperty("id", "rs_" + UUID.randomUUID().toString().substring(0, 8));
        JsonArray content = new JsonArray();
        JsonObject reasoningText = new JsonObject();
        reasoningText.addProperty("type", "reasoning_text");
        reasoningText.addProperty("text", text);
        content.add(reasoningText);
        item.add("content", content);
        sb.append(GSON.toJson(item)).append('\n');
    }

    private static void writeToolItems(@NotNull JsonObject part, @NotNull StringBuilder sb) {
        JsonObject invocation = part.getAsJsonObject("toolInvocation");
        if (invocation == null) return;

        String state = getStr(invocation, "state");
        String callId = getStr(invocation, "toolCallId");
        if (callId == null) callId = "call_" + UUID.randomUUID().toString().substring(0, 8);

        String toolName = getStr(invocation, "toolName");
        if (toolName == null) toolName = "unknown";

        // Write function_call
        if ("call".equals(state) || "result".equals(state)) {
            JsonObject callItem = new JsonObject();
            callItem.addProperty("type", "function_call");
            callItem.addProperty("call_id", callId);
            callItem.addProperty("name", toolName);
            callItem.addProperty("id", "fc_" + UUID.randomUUID().toString().substring(0, 8));

            JsonElement args = invocation.get("args");
            callItem.addProperty("arguments", args != null ? GSON.toJson(args) : "{}");
            sb.append(GSON.toJson(callItem)).append('\n');
        }

        // Write function_call_output
        if ("result".equals(state)) {
            String result = getStr(invocation, "result");
            JsonObject outputItem = new JsonObject();
            outputItem.addProperty("type", "function_call_output");
            outputItem.addProperty("call_id", callId);
            outputItem.addProperty("output", result != null ? result : "");
            sb.append(GSON.toJson(outputItem)).append('\n');
        }
    }

    /**
     * Inserts a thread record into the Codex SQLite database.
     */
    private static void insertThread(@NotNull Path dbPath, @NotNull String threadId, @NotNull String rolloutPath) {
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url);
             Statement pragmaStmt = conn.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode=WAL");

            // Ensure threads table exists (Codex may not have created it yet)
            pragmaStmt.execute("""
                    CREATE TABLE IF NOT EXISTS threads (
                        id TEXT PRIMARY KEY,
                        rollout_path TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        archived INTEGER DEFAULT 0,
                        memory_mode TEXT
                    )""");

            long now = System.currentTimeMillis() / 1000; // Codex uses epoch seconds
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO threads (id, rollout_path, created_at, updated_at, archived) VALUES (?, ?, ?, ?, 0)")) {
                ps.setString(1, threadId);
                ps.setString(2, rolloutPath);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            LOG.info("Inserted Codex thread record: " + threadId);
        } catch (SQLException e) {
            LOG.warn("Failed to insert Codex thread record into " + dbPath, e);
        }
    }

    @Nullable
    private static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }
}
