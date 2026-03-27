package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 {@link SessionMessage} list into OpenCode's native SQLite format.
 *
 * <p>OpenCode stores sessions in {@code opencode.db} with three tables:
 * {@code session}, {@code message}, and {@code part}. This exporter creates a new
 * session record and populates the message/part tables so that OpenCode can resume
 * the conversation.</p>
 */
public final class OpenCodeClientExporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private OpenCodeClientExporter() {
    }

    @NotNull
    public static Path defaultDbPath() {
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isEmpty()) {
            return Path.of(xdgData, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    /**
     * Exports v2 session messages into the OpenCode SQLite database.
     *
     * @param messages   the v2 session messages to export
     * @param dbPath     path to the OpenCode database file
     * @param projectDir the project directory (used as the session's {@code directory} field)
     * @return the new session ID, or {@code null} if export failed
     */
    @Nullable
    public static String exportSession(
        @NotNull List<SessionMessage> messages,
        @NotNull Path dbPath,
        @NotNull String projectDir) {

        if (messages.isEmpty()) return null;

        if (!Files.exists(dbPath)) {
            LOG.info("OpenCode database not found at " + dbPath + " — skipping export");
            return null;
        }

        String sessionId = UUID.randomUUID().toString();
        String url = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA journal_mode=WAL");
            }

            ensureTables(conn);
            long now = System.currentTimeMillis();

            insertSession(conn, sessionId, projectDir, now);

            for (SessionMessage msg : messages) {
                if ("separator".equals(msg.role)) continue;
                insertMessage(conn, sessionId, msg, now);
            }

            LOG.info("Exported v2 session to OpenCode: " + sessionId);
            return sessionId;
        } catch (SQLException e) {
            LOG.warn("Failed to export v2 session to OpenCode database: " + dbPath, e);
            return null;
        }
    }

    private static void ensureTables(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session (
                    id TEXT PRIMARY KEY,
                    directory TEXT NOT NULL,
                    title TEXT,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS message (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    data TEXT,
                    time_created INTEGER NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session(id)
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS part (
                    id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    data TEXT,
                    time_created INTEGER NOT NULL,
                    FOREIGN KEY (message_id) REFERENCES message(id)
                )""");
        }
    }

    private static void insertSession(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull String projectDir,
        long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session (id, directory, title, time_created, time_updated) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, projectDir);
            ps.setString(3, "IDE Agent Session");
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private static void insertMessage(
        @NotNull Connection conn,
        @NotNull String sessionId,
        @NotNull SessionMessage msg,
        long baseTime) throws SQLException {

        String messageId = msg.id;
        long timeCreated = msg.createdAt > 0 ? msg.createdAt : baseTime;

        // Build message-level data JSON (role + embedded parts for OpenCode's schema)
        JsonObject msgData = new JsonObject();
        msgData.addProperty("role", msg.role);

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO message (id, session_id, data, time_created) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, messageId);
            ps.setString(2, sessionId);
            ps.setString(3, GSON.toJson(msgData));
            ps.setLong(4, timeCreated);
            ps.executeUpdate();
        }

        // Insert each part
        for (JsonObject part : msg.parts) {
            insertPart(conn, messageId, part, timeCreated);
        }
    }

    private static void insertPart(
        @NotNull Connection conn,
        @NotNull String messageId,
        @NotNull JsonObject v2Part,
        long timeCreated) throws SQLException {

        String partId = UUID.randomUUID().toString();
        JsonObject partData = convertV2PartToOpenCodePart(v2Part);

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO part (id, message_id, data, time_created) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, partId);
            ps.setString(2, messageId);
            ps.setString(3, GSON.toJson(partData));
            ps.setLong(4, timeCreated);
            ps.executeUpdate();
        }
    }

    @NotNull
    private static JsonObject convertV2PartToOpenCodePart(@NotNull JsonObject v2Part) {
        String type = JsonlUtil.getStr(v2Part, "type");
        if (type == null) return v2Part.deepCopy();

        JsonObject result = new JsonObject();
        switch (type) {
            case "text", "reasoning" -> {
                result.addProperty("type", type);
                String text = JsonlUtil.getStr(v2Part, "text");
                result.addProperty("text", text != null ? text : "");
            }
            case "tool-invocation" -> {
                JsonObject invocation = v2Part.getAsJsonObject("toolInvocation");
                if (invocation != null) {
                    result.addProperty("type", "tool-invocation");
                    result.add("toolInvocation", invocation.deepCopy());
                }
            }
            default -> {
                return v2Part.deepCopy();
            }
        }
        return result;
    }
}
