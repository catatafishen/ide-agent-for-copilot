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

/**
 * Imports OpenCode sessions from {@code ~/.local/share/opencode/opencode.db} (SQLite).
 * <p>
 * OpenCode's part format is structurally very close to the v2 universal format,
 * so import is nearly a direct mapping.
 */
public final class OpenCodeImporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeImporter.class);
    private static final Gson GSON = new Gson();

    private OpenCodeImporter() {
    }

    /**
     * Returns the default OpenCode database path, respecting XDG_DATA_HOME.
     */
    @NotNull
    public static Path defaultDbPath() {
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isEmpty()) {
            return Path.of(xdgData, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    /**
     * Finds the most recently updated session for the given project directory
     * and imports its messages and parts into v2 session messages.
     *
     * @param dbPath     path to opencode.db
     * @param projectDir project directory to match sessions against
     * @return imported messages, or empty list if no session found or import fails
     */
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

    /**
     * Queries the session table for the most recently updated session matching the project directory.
     */
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

    /**
     * Imports all messages and their parts for a given session ID.
     */
    @NotNull
    private static List<SessionMessage> importSession(@NotNull Path dbPath, @NotNull String sessionId) {
        String url = "jdbc:sqlite:" + dbPath;
        List<SessionMessage> result = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url);
             Statement pragmaStmt = conn.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode=WAL");

            // Load all messages for this session, ordered by creation time
            List<MessageRow> messageRows = loadMessages(conn, sessionId);

            // For each message, load its parts
            for (MessageRow msgRow : messageRows) {
                List<JsonObject> parts = loadParts(conn, msgRow.id);
                if (parts.isEmpty() && msgRow.role != null) {
                    // Message with no parts — try to extract text from message.data
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
                    String role = dataObj != null ? getStr(dataObj, "role") : null;

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
                        // OpenCode part format is structurally close to v2 — use directly
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

        // message.data may contain inline "parts" with text
        if (obj.has("parts") && obj.get("parts").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : obj.getAsJsonArray("parts")) {
                if (!el.isJsonObject()) continue;
                JsonObject part = el.getAsJsonObject();
                if ("text".equals(getStr(part, "type"))) {
                    String text = getStr(part, "text");
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
        return assistant != null ? getStr(assistant, "modelID") : null;
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

    @Nullable
    private static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        var el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
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
