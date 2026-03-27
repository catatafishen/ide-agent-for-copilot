package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public final class OpenCodeClientImporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeClientImporter.class);
    private static final Gson GSON = new Gson();

    private OpenCodeClientImporter() {
    }

    @NotNull
    public static Path defaultDbPath() {
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isEmpty()) {
            return Path.of(xdgData, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    @NotNull
    public static List<SessionMessage> importLatestSession(@NotNull Path dbPath, @NotNull String projectDir) {
        if (!Files.exists(dbPath)) {
            LOG.info("OpenCode database not found at " + dbPath);
            return List.of();
        }

        String sessionId = findLatestSessionId(dbPath, projectDir);
        if (sessionId == null) {
            LOG.info("No OpenCode session found for project: " + projectDir);
            return List.of();
        }

        return importSession(dbPath, sessionId);
    }

    @Nullable
    private static String findLatestSessionId(@NotNull Path dbPath, @NotNull String projectDir) {
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url);
             Statement pragmaStmt = conn.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode=WAL");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM session WHERE directory = ? ORDER BY time_updated DESC LIMIT 1")) {
                ps.setString(1, projectDir);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("id");
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query OpenCode database: " + dbPath, e);
        }
        return null;
    }

    @NotNull
    private static List<SessionMessage> importSession(@NotNull Path dbPath, @NotNull String sessionId) {
        String url = "jdbc:sqlite:" + dbPath;
        List<SessionMessage> result = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url);
             Statement pragmaStmt = conn.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode=WAL");

            List<MessageRow> messageRows = loadMessages(conn, sessionId);

            for (MessageRow msgRow : messageRows) {
                List<JsonObject> parts = loadParts(conn, msgRow.id);
                if (parts.isEmpty() && msgRow.role != null) {
                    String text = extractTextFromMessageData(msgRow.data);
                    if (text != null) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", text);
                        parts = List.of(textPart);
                    }
                }

                if (!parts.isEmpty()) {
                    result.add(new SessionMessage(
                            msgRow.id,
                            msgRow.role != null ? msgRow.role : "assistant",
                            parts,
                            msgRow.timeCreated,
                            "OpenCode",
                            extractModel(msgRow.data)
                    ));
                }
            }

        } catch (SQLException e) {
            LOG.warn("Failed to import OpenCode session " + sessionId + " from " + dbPath, e);
        }

        return result;
    }

    @NotNull
    private static List<MessageRow> loadMessages(@NotNull Connection conn, @NotNull String sessionId) throws SQLException {
        List<MessageRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, data, time_created FROM message WHERE session_id = ? ORDER BY time_created ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String data = rs.getString("data");
                    long timeCreated = rs.getLong("time_created");

                    JsonObject dataObj = parseJson(data);
                    String role = dataObj != null ? JsonlUtil.getStr(dataObj, "role") : null;

                    rows.add(new MessageRow(id, role, data, dataObj, timeCreated));
                }
            }
        }
        return rows;
    }

    @NotNull
    private static List<JsonObject> loadParts(@NotNull Connection conn, @NotNull String messageId) throws SQLException {
        List<JsonObject> parts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT data FROM part WHERE message_id = ? ORDER BY time_created ASC")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String data = rs.getString("data");
                    JsonObject partData = parseJson(data);
                    if (partData != null) {
                        parts.add(partData);
                    }
                }
            }
        }
        return parts;
    }

    @Nullable
    private static String extractTextFromMessageData(@Nullable String data) {
        if (data == null) return null;
        JsonObject obj = parseJson(data);
        if (obj == null) return null;

        if (obj.has("parts") && obj.get("parts").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : obj.getAsJsonArray("parts")) {
                if (!el.isJsonObject()) continue;
                JsonObject part = el.getAsJsonObject();
                if ("text".equals(JsonlUtil.getStr(part, "type"))) {
                    String text = JsonlUtil.getStr(part, "text");
                    if (text != null) sb.append(text);
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return null;
    }

    @Nullable
    private static String extractModel(@Nullable String data) {
        if (data == null) return null;
        JsonObject obj = parseJson(data);
        if (obj == null || !obj.has("metadata")) return null;
        JsonObject metadata = obj.getAsJsonObject("metadata");
        if (metadata == null || !metadata.has("assistant")) return null;
        JsonObject assistant = metadata.getAsJsonObject("assistant");
        return assistant != null ? JsonlUtil.getStr(assistant, "modelID") : null;
    }

    @Nullable
    private static JsonObject parseJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private record MessageRow(
            @NotNull String id,
            @Nullable String role,
            @Nullable String data,
            @Nullable JsonObject dataObj,
            long timeCreated
    ) {
    }
}
