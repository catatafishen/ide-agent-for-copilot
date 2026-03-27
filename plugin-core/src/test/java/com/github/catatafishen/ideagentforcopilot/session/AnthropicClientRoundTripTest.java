package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.AnthropicClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnthropicClientImporter} and {@link AnthropicClientExporter}.
 * Validates import, export, and round-trip conversion between the Anthropic Messages
 * API JSONL format (used by Claude CLI, Kiro, and Junie) and the v2 {@link SessionMessage} model.
 */
class AnthropicClientRoundTripTest {

    @TempDir
    Path tempDir;

    // ── Import tests ────────────────────────────────────────────────

    @Test
    void importBasicConversation() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Hello"}]}
            {"role":"assistant","content":[{"type":"text","text":"Hi there!"}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        assertEquals(2, messages.size());

        assertEquals("user", messages.get(0).role);
        assertEquals("Hello", extractText(messages.get(0)));

        assertEquals("assistant", messages.get(1).role);
        assertEquals("Hi there!", extractText(messages.get(1)));
    }

    @Test
    void importWithToolUseAndResult() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read a file"}]}
            {"role":"assistant","content":[{"type":"text","text":"I will read it."},{"type":"tool_use","id":"tu1","name":"read_file","input":{"path":"/test.txt"}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"file contents"}]}
            {"role":"assistant","content":[{"type":"text","text":"The file says: file contents"}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        // The tool_result-only user message should be skipped
        assertEquals(3, messages.size());

        assertEquals("user", messages.get(0).role);
        assertEquals("Read a file", extractText(messages.get(0)));

        SessionMessage assistant1 = messages.get(1);
        assertEquals("assistant", assistant1.role);
        assertTrue(assistant1.parts.size() >= 2, "Should have text + tool parts");

        boolean foundToolResult = false;
        for (JsonObject part : assistant1.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("tu1", inv.get("toolCallId").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("file contents", inv.get("result").getAsString());
                foundToolResult = true;
            }
        }
        assertTrue(foundToolResult, "First assistant message should have resolved tool result");
    }

    @Test
    void importSkipsToolResultOnlyUserMessages() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Hi"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"ls","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"dir listing"}]}
            {"role":"assistant","content":[{"type":"text","text":"Done."}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        // user("Hi"), assistant(tool_use), assistant("Done.") — tool_result user message skipped
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("assistant", messages.get(1).role);
        assertEquals("assistant", messages.get(2).role);
    }

    @Test
    void importToolResultWithArrayContent() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read files"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"read","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":[{"text":"line1"},{"text":"line2"}]}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        for (JsonObject part : assistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("line1\nline2", inv.get("result").getAsString());
            }
        }
    }

    @Test
    void importEmptyFileReturnsEmptyList() throws IOException {
        assertEquals(0, importJsonl("").size());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportProducesAnthropicMessagesFormat() throws IOException {
        List<SessionMessage> messages = List.of(
            userMessage("Hello"),
            assistantMessage("Hi!")
        );

        Path target = tempDir.resolve("exported.jsonl");
        AnthropicClientExporter.exportToFile(messages, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"role\":\"user\""));
        assertTrue(content.contains("\"role\":\"assistant\""));
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("Hi!"));
    }

    @Test
    void exportToolInvocationsProduceToolUseAndToolResult() throws IOException {
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "data");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart), System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("exported-tools.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("read"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"tool_use\""));
        assertTrue(content.contains("\"type\":\"tool_result\""));
        assertTrue(content.contains("\"tool_use_id\":\"tc1\""));
    }

    @Test
    void exportSkipsReasoningParts() throws IOException {
        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "Thinking...");

        JsonObject textPart = textPart("Answer");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(reasoningPart, textPart),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("exported-reasoning.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Q"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("Thinking..."), "Reasoning should not appear in Anthropic export");
        assertTrue(content.contains("Answer"));
    }

    @Test
    void exportAppliesTokenBudget() throws IOException {
        SessionMessage longMsg = assistantMessage("x".repeat(80_000)); // ~20000 tokens
        List<SessionMessage> messages = List.of(
            userMessage("old question"),
            assistantMessage("old answer"),
            userMessage("new question"),
            longMsg
        );

        Path target = tempDir.resolve("budget.jsonl");
        // With budget of 20000, the long message alone fills the budget
        AnthropicClientExporter.exportToFile(messages, target, 20_000);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        // The most recent messages should be kept, older ones may be trimmed
        assertTrue(content.contains("x".repeat(100)), "Most recent content should be present");
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextConversation() throws IOException {
        List<SessionMessage> original = List.of(
            userMessage("What is Rust?"),
            assistantMessage("A systems programming language.")
        );

        Path file = tempDir.resolve("roundtrip.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(2, imported.size());
        assertEquals("What is Rust?", extractText(imported.get(0)));
        assertEquals("A systems programming language.", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolInvocations() throws IOException {
        JsonObject textPart = textPart("Reading file");
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a.txt\"}", "hello world");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, toolPart),
            System.currentTimeMillis(), null, null);

        List<SessionMessage> original = List.of(userMessage("Read /a.txt"), assistant);

        Path file = tempDir.resolve("roundtrip-tools.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(2, imported.size());
        SessionMessage importedAssistant = imported.get(1);

        boolean foundTool = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("hello world", inv.get("result").getAsString());
            }
        }
        assertTrue(foundTool);
    }

    @Test
    void roundTripMultipleTurns() throws IOException {
        List<SessionMessage> original = List.of(
            userMessage("Question 1"),
            assistantMessage("Answer 1"),
            userMessage("Question 2"),
            assistantMessage("Answer 2")
        );

        Path file = tempDir.resolve("roundtrip-multi.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(4, imported.size());
        assertEquals("Question 1", extractText(imported.get(0)));
        assertEquals("Answer 1", extractText(imported.get(1)));
        assertEquals("Question 2", extractText(imported.get(2)));
        assertEquals("Answer 2", extractText(imported.get(3)));
    }

    // ── Helper methods ──────────────────────────────────────────────

    private List<SessionMessage> importJsonl(String jsonl) throws IOException {
        Path file = tempDir.resolve("test.jsonl");
        Files.writeString(file, jsonl, StandardCharsets.UTF_8);
        return AnthropicClientImporter.importFile(file);
    }

    private static SessionMessage userMessage(String text) {
        return new SessionMessage("u-" + text.hashCode(), "user", List.of(textPart(text)),
            System.currentTimeMillis(), null, null);
    }

    private static SessionMessage assistantMessage(String text) {
        return new SessionMessage("a-" + text.hashCode(), "assistant", List.of(textPart(text)),
            System.currentTimeMillis(), null, null);
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject toolInvocationPart(String callId, String toolName, String args, String result) {
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

    private static String extractText(SessionMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject part : msg.parts) {
            if ("text".equals(part.get("type").getAsString())) {
                sb.append(part.get("text").getAsString());
            }
        }
        return sb.toString();
    }
}
