package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates exported OpenCode session data against OpenCode 1.2.27's actual Zod schemas.
 *
 * <p>Runs a Node.js validation script that defines the exact Zod schemas extracted from
 * the OpenCode binary and validates the exported message/part data against them. This
 * catches schema mismatches that would cause "schema validation failure" errors at runtime.</p>
 */
class OpenCodeZodValidationTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PROJECT_DIR = "/tmp/test-project";

    private static Path validatorDir;
    private static String nodeCmd;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void ensureNodeAndZod() throws IOException, InterruptedException {
        // Resolve the validator directory (relative to project root)
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        // Walk up from test working dir if needed (Gradle sets it to the module dir)
        validatorDir = projectRoot.resolve("opencode-zod-validator");
        if (!Files.exists(validatorDir)) {
            validatorDir = projectRoot.getParent().resolve("plugin-core/opencode-zod-validator");
        }
        if (!Files.exists(validatorDir.resolve("validate.mjs"))) {
            fail("Zod validator script not found at " + validatorDir
                + ". Ensure plugin-core/opencode-zod-validator/validate.mjs exists.");
        }

        nodeCmd = findNode();

        // Ensure node_modules/zod is installed
        if (!Files.exists(validatorDir.resolve("node_modules/zod"))) {
            String npmCmd = findNpm();
            ProcessBuilder pb = new ProcessBuilder(npmCmd, "install")
                .directory(validatorDir.toFile())
                .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) {
                fail("npm install failed in " + validatorDir + ": " + output);
            }
        }
    }

    @Test
    void exportedSessionPassesZodValidation() throws Exception {
        // Build a realistic session with user/assistant messages and tool calls
        JsonObject toolPart = toolInvocationPart("call-1", "read_file",
            "{\"path\":\"test.txt\"}", "file content");
        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "Let me think...");

        List<SessionMessage> messages = List.of(
            userMessage("What is in test.txt?"),
            new SessionMessage("a1", "assistant",
                List.of(reasoningPart, toolPart, textPart("Here is the content.")),
                System.currentTimeMillis(), "build", "claude-sonnet-4.6"),
            userMessage("Thanks!"),
            new SessionMessage("a2", "assistant",
                List.of(textPart("You're welcome!")),
                System.currentTimeMillis(), "build", "claude-sonnet-4.6")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(messages, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty(), "Export should succeed");

        // Extract message and part rows from the exported DB
        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);

        // Run Zod validation
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Exported session data should pass Zod validation but got errors:\n" + result);
    }

    @Test
    void exportedSubagentPartPassesZodValidation() throws Exception {
        JsonObject subagentPart = new JsonObject();
        subagentPart.addProperty("type", "subagent");
        subagentPart.addProperty("agentType", "explore");
        subagentPart.addProperty("description", "Exploring codebase");
        subagentPart.addProperty("result", "Found 3 files");

        List<SessionMessage> messages = List.of(
            userMessage("Search"),
            new SessionMessage("a1", "assistant", List.of(subagentPart),
                System.currentTimeMillis(), null, null)
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(messages, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty());

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Subagent→text conversion should pass Zod validation:\n" + result);
    }

    @Test
    void exportedRunningToolPartPassesZodValidation() throws Exception {
        // Tool invocation in "partial-call" state (still running)
        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "call");
        invocation.addProperty("toolCallId", "call-running");
        invocation.addProperty("toolName", "bash");
        invocation.addProperty("args", "{\"command\":\"ls\"}");

        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");
        toolPart.add("toolInvocation", invocation);

        List<SessionMessage> messages = List.of(
            userMessage("Run ls"),
            new SessionMessage("a1", "assistant", List.of(toolPart),
                System.currentTimeMillis(), null, null)
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(messages, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty());

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Running tool part should pass Zod validation:\n" + result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private JsonObject extractValidatorInput(Path dbPath, String sessionId) throws SQLException {
        JsonArray messagesArr = new JsonArray();
        JsonArray partsArr = new JsonArray();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Load SQLite JDBC driver (IntelliJ classloader workaround)
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found", e);
            }

            try (var ps = conn.prepareStatement(
                "SELECT id, session_id, data FROM message WHERE session_id = ? ORDER BY time_created")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("id", rs.getString("id"));
                        row.addProperty("session_id", rs.getString("session_id"));
                        row.add("data", JsonParser.parseString(rs.getString("data")));
                        messagesArr.add(row);
                    }
                }
            }

            try (var ps = conn.prepareStatement(
                "SELECT id, session_id, message_id, data FROM part WHERE session_id = ? ORDER BY id")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("id", rs.getString("id"));
                        row.addProperty("session_id", rs.getString("session_id"));
                        row.addProperty("message_id", rs.getString("message_id"));
                        row.add("data", JsonParser.parseString(rs.getString("data")));
                        partsArr.add(row);
                    }
                }
            }
        }

        JsonObject input = new JsonObject();
        input.add("messages", messagesArr);
        input.add("parts", partsArr);
        return input;
    }

    private String runValidator(JsonObject input) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(nodeCmd, "validate.mjs")
            .directory(validatorDir.toFile())
            .redirectErrorStream(true);
        Process proc = pb.start();

        try (OutputStream os = proc.getOutputStream()) {
            os.write(GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = proc.waitFor();

        if (exitCode != 0 && exitCode != 1) {
            fail("Validator script crashed (exit " + exitCode + "): " + output);
        }

        return output;
    }

    private static SessionMessage userMessage(String text) {
        return new SessionMessage("u-" + text.hashCode(), "user",
            List.of(textPart(text)), System.currentTimeMillis(), null, null);
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject toolInvocationPart(String callId, String toolName,
                                                  String args, String result) {
        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "result");
        invocation.addProperty("toolCallId", callId);
        invocation.addProperty("toolName", toolName);
        invocation.addProperty("args", args);
        invocation.addProperty("result", result);

        JsonObject part = new JsonObject();
        part.addProperty("type", "tool-invocation");
        part.add("toolInvocation", invocation);
        return part;
    }

    private static String findNode() {
        return findBinary("node");
    }

    private static String findNpm() {
        return findBinary("npm");
    }

    /**
     * Finds a binary on PATH or in common nvm locations.
     */
    private static String findBinary(String name) {
        // Check nvm
        Path nvmDir = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (Files.isDirectory(nvmDir)) {
            try (var versions = Files.list(nvmDir)) {
                Path latest = versions
                    .filter(Files::isDirectory)
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .findFirst().orElse(null);
                if (latest != null) {
                    Path bin = latest.resolve("bin").resolve(name);
                    if (Files.isExecutable(bin)) return bin.toString();
                }
            } catch (IOException ignored) {
                // fall through to PATH
            }
        }
        return name;
    }
}
