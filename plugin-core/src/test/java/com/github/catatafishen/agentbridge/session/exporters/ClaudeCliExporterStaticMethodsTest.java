package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the package-private pure static helpers in {@link ClaudeCliExporter}:
 * {@code extractLastUserPromptText} and {@code hasContentBlockType}.
 */
class ClaudeCliExporterStaticMethodsTest {

    // ── extractLastUserPromptText ────────────────────────────────────────────

    @Test
    void extractLastUserPromptText_emptyList_returnsEmptyString() {
        assertEquals("", ClaudeCliExporter.extractLastUserPromptText(List.of()));
    }

    @Test
    void extractLastUserPromptText_singleUserMessage_returnsItsText() {
        AnthropicMessage user = userMsg("Hello world");
        assertEquals("Hello world", ClaudeCliExporter.extractLastUserPromptText(List.of(user)));
    }

    @Test
    void extractLastUserPromptText_multipleUserMessages_returnsLastOne() {
        AnthropicMessage u1 = userMsg("First");
        AnthropicMessage assistant = assistantMsg("Reply");
        AnthropicMessage u2 = userMsg("Second");

        assertEquals("Second",
            ClaudeCliExporter.extractLastUserPromptText(List.of(u1, assistant, u2)));
    }

    @Test
    void extractLastUserPromptText_onlyAssistantMessages_returnsEmpty() {
        AnthropicMessage a1 = assistantMsg("Reply1");
        AnthropicMessage a2 = assistantMsg("Reply2");

        assertEquals("", ClaudeCliExporter.extractLastUserPromptText(List.of(a1, a2)));
    }

    @Test
    void extractLastUserPromptText_userMessageWithMultipleTextBlocks_concatenates() {
        JsonObject block1 = textBlock("Hello ");
        JsonObject block2 = textBlock("World");
        AnthropicMessage user = new AnthropicMessage("user", List.of(block1, block2), 0, "");

        assertEquals("Hello World", ClaudeCliExporter.extractLastUserPromptText(List.of(user)));
    }

    @Test
    void extractLastUserPromptText_userMessageWithToolResultBlocks_skipsNonText() {
        // tool_result blocks have "type" but no "text" — should be skipped
        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", "123");
        toolResult.addProperty("content", "result data");
        AnthropicMessage user = new AnthropicMessage("user", List.of(toolResult), 0, "");

        // No text blocks, so the last text seen for this user msg is "" → the running
        // accumulator is empty → but text update didn't fire → lastText stays ""
        assertEquals("", ClaudeCliExporter.extractLastUserPromptText(List.of(user)));
    }

    @Test
    void extractLastUserPromptText_mixedUserMessages_lastTextBlockUserWins() {
        AnthropicMessage u1 = userMsg("First question");
        AnthropicMessage a1 = assistantMsg("Answer");

        // Second user message is tool_result only (no text blocks)
        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", "abc");
        toolResult.addProperty("content", "tool output");
        AnthropicMessage u2 = new AnthropicMessage("user", List.of(toolResult), 0, "");

        AnthropicMessage a2 = assistantMsg("Done");
        AnthropicMessage u3 = userMsg("Final prompt");

        // u3 has text, so that's the last user prompt text
        assertEquals("Final prompt",
            ClaudeCliExporter.extractLastUserPromptText(List.of(u1, a1, u2, a2, u3)));
    }

    // ── hasContentBlockType ──────────────────────────────────────────────────

    @Test
    void hasContentBlockType_matchingRoleAndType_returnsTrue() {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", "123");
        AnthropicMessage msg = new AnthropicMessage("assistant", List.of(block), 0, "");

        assertTrue(ClaudeCliExporter.hasContentBlockType(msg, "assistant", "tool_use"));
    }

    @Test
    void hasContentBlockType_wrongRole_returnsFalse() {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        AnthropicMessage msg = new AnthropicMessage("assistant", List.of(block), 0, "");

        assertFalse(ClaudeCliExporter.hasContentBlockType(msg, "user", "tool_use"));
    }

    @Test
    void hasContentBlockType_wrongBlockType_returnsFalse() {
        JsonObject block = textBlock("hello");
        AnthropicMessage msg = new AnthropicMessage("assistant", List.of(block), 0, "");

        assertFalse(ClaudeCliExporter.hasContentBlockType(msg, "assistant", "tool_use"));
    }

    @Test
    void hasContentBlockType_emptyBlocks_returnsFalse() {
        AnthropicMessage msg = new AnthropicMessage("user", List.of(), 0, "");

        assertFalse(ClaudeCliExporter.hasContentBlockType(msg, "user", "text"));
    }

    @Test
    void hasContentBlockType_blockWithoutTypeField_returnsFalse() {
        JsonObject block = new JsonObject();
        block.addProperty("text", "hello");
        // no "type" field
        AnthropicMessage msg = new AnthropicMessage("user", List.of(block), 0, "");

        assertFalse(ClaudeCliExporter.hasContentBlockType(msg, "user", "text"));
    }

    @Test
    void hasContentBlockType_multipleBlocks_findsMatchAmongThem() {
        JsonObject textB = textBlock("hello");
        JsonObject toolUseB = new JsonObject();
        toolUseB.addProperty("type", "tool_use");
        toolUseB.addProperty("id", "abc");
        AnthropicMessage msg = new AnthropicMessage("assistant", List.of(textB, toolUseB), 0, "");

        assertTrue(ClaudeCliExporter.hasContentBlockType(msg, "assistant", "tool_use"));
        assertTrue(ClaudeCliExporter.hasContentBlockType(msg, "assistant", "text"));
    }

    @Test
    void hasContentBlockType_toolResult_matchesUserRole() {
        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", "abc");
        AnthropicMessage msg = new AnthropicMessage("user", List.of(toolResult), 0, "");

        assertTrue(ClaudeCliExporter.hasContentBlockType(msg, "user", "tool_result"));
        assertFalse(ClaudeCliExporter.hasContentBlockType(msg, "assistant", "tool_result"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AnthropicMessage userMsg(String text) {
        return new AnthropicMessage("user", List.of(textBlock(text)), 0, "");
    }

    private static AnthropicMessage assistantMsg(String text) {
        return new AnthropicMessage("assistant", List.of(textBlock(text)), 0, "");
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }
}
