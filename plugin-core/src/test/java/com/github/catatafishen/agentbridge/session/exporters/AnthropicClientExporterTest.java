package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnthropicClientExporter} — specifically the package-private
 * {@code toAnthropicMessages} conversion and {@code trimToSizeBudget} helper.
 */
class AnthropicClientExporterTest {

    // ── toAnthropicMessages — basic entry types ──────────────────────────────

    @Test
    void emptyEntriesProducesEmptyMessageList() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(List.of());
        assertTrue(messages.isEmpty(), "No entries should produce no messages");
    }

    @Test
    void singlePromptProducesOneUserMessageWithTextBlock() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Hello world")));

        assertEquals(1, messages.size());
        AnthropicMessage msg = messages.get(0);
        assertEquals("user", msg.role());
        assertEquals(1, msg.contentBlocks().size());
        JsonObject block = msg.contentBlocks().get(0);
        assertEquals("text", block.get("type").getAsString());
        assertEquals("Hello world", block.get("text").getAsString());
    }

    @Test
    void promptAndTextProduceUserThenAssistantMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Question"), text("Answer")));

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
    }

    @Test
    void textBlockContentMatchesRawText() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Q"), text("The answer is 42")));

        JsonObject block = messages.get(1).contentBlocks().get(0);
        assertEquals("text", block.get("type").getAsString());
        assertEquals("The answer is 42", block.get("text").getAsString());
    }

    @Test
    void emptyPromptTextIsSkippedAndProducesNoMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(new EntryData.Prompt("")));
        assertTrue(messages.isEmpty(), "Empty prompt text must produce no message");
    }

    @Test
    void emptyTextEntryIsSkippedAndProducesNoAssistantMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Hello"), new EntryData.Text("")));
        // Only the user prompt message — the empty text should not add an assistant message
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
    }

    // ── toAnthropicMessages — tool calls ─────────────────────────────────────

    @Test
    void singleToolCallProducesToolUseInAssistantAndToolResultInUser() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                prompt("Read a file"),
                toolCall("read_file", "{\"path\":\"/tmp/test.txt\"}", "file contents here")));

        // user(prompt) + assistant(tool_use) + user(tool_result)
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("user", messages.get(2).role());
    }

    @Test
    void toolUseBlockHasRequiredFields() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Go"), toolCall("my_tool", "{\"k\":\"v\"}", "ok")));

        JsonObject toolUse = messages.get(1).contentBlocks().get(0);
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertEquals("my_tool", toolUse.get("name").getAsString());
        assertTrue(toolUse.has("id"), "tool_use block must have an id");
        assertTrue(toolUse.has("input"), "tool_use block must have an input");
        assertTrue(toolUse.get("input").getAsJsonObject().has("k"));
        assertEquals("v", toolUse.get("input").getAsJsonObject().get("k").getAsString());
    }

    @Test
    void toolResultBlockLinksToToolUseById() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Go"), toolCall("ls", "{}", "file_a\nfile_b")));

        String toolUseId = messages.get(1).contentBlocks().get(0).get("id").getAsString();
        JsonObject toolResult = messages.get(2).contentBlocks().get(0);
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals(toolUseId, toolResult.get("tool_use_id").getAsString());
        assertEquals("file_a\nfile_b", toolResult.get("content").getAsString());
    }

    @Test
    void multipleToolCallsInSameTurnAreGroupedIntoSameAssistantMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                prompt("Do two things"),
                toolCall("tool_a", "{}", "result_a"),
                toolCall("tool_b", "{}", "result_b")
            ));

        // user(prompt) + assistant(2 × tool_use) + user(2 × tool_result)
        assertEquals(3, messages.size());
        assertEquals(2, messages.get(1).contentBlocks().size(),
            "Both tool_use blocks must be in the same assistant message");
        assertEquals("tool_use", messages.get(1).contentBlocks().get(0).get("type").getAsString());
        assertEquals("tool_use", messages.get(1).contentBlocks().get(1).get("type").getAsString());
        assertEquals(2, messages.get(2).contentBlocks().size(),
            "Both tool_result blocks must be in the same user message");
    }

    @Test
    void textEntryAfterToolCallStartsNewAssistantTurn() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                prompt("Step 1"),
                toolCall("run_cmd", "{}", "output"),
                text("I used the command and here is the conclusion.")
            ));

        // user(prompt) + assistant(tool_use) + user(tool_result) + assistant(text)
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("user", messages.get(2).role());
        assertEquals("assistant", messages.get(3).role());
        // The last assistant message should be a text block
        JsonObject lastBlock = messages.get(3).contentBlocks().get(0);
        assertEquals("text", lastBlock.get("type").getAsString());
    }

    @Test
    void toolCallWithNullArgsDefaultsToEmptyInputObject() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Go"), new EntryData.ToolCall("my_tool", null, "other", "done")));

        JsonObject toolUse = messages.get(1).contentBlocks().get(0);
        JsonObject input = toolUse.getAsJsonObject("input");
        assertNotNull(input, "input must not be null even when args is null");
        assertEquals(0, input.size(), "null args should produce an empty input object");
    }

    @Test
    void toolCallWithInvalidJsonArgsWrapsContentAsRaw() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Go"), toolCall("my_tool", "not-valid-json", "result")));

        JsonObject toolUse = messages.get(1).contentBlocks().get(0);
        JsonObject input = toolUse.getAsJsonObject("input");
        assertTrue(input.has("_raw"),
            "Invalid JSON args must be wrapped with '_raw' key; got: " + input);
        assertEquals("not-valid-json", input.get("_raw").getAsString());
    }

    @Test
    void toolCallWithNullResultDefaultsToEmptyString() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Go"), new EntryData.ToolCall("t", "{}", "other", null)));

        JsonObject toolResult = messages.get(2).contentBlocks().get(0);
        assertEquals("", toolResult.get("content").getAsString());
    }

    // ── toAnthropicMessages — Thinking entries ───────────────────────────────

    @Test
    void thinkingEntryDoesNotAddContentBlockToOutput() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                prompt("Explain something"),
                new EntryData.Thinking("Let me reason through this...", ""),
                text("Here is my answer.")
            ));

        // user(prompt) + assistant(text only — thinking block excluded)
        assertEquals(2, messages.size());
        assertEquals(1, messages.get(1).contentBlocks().size(),
            "Thinking block must not appear in assistant content");
        assertEquals("text", messages.get(1).contentBlocks().get(0).get("type").getAsString());
    }

    @Test
    void thinkingEntryPropagatesModelToSubsequentAssistantMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(
                prompt("Q"),
                new EntryData.Text("A", Instant.now().toString(), "", "claude-opus-4", "")
            ));

        assertEquals(2, messages.size());
        assertEquals("claude-opus-4", messages.get(1).model());
    }

    // ── toAnthropicMessages — merging and timestamps ─────────────────────────

    @Test
    void consecutivePromptsAreMergedIntoSingleUserMessage() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("First part."), prompt("Second part.")));

        // After merge-consecutive-same-role: 1 merged user message
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals(2, messages.get(0).contentBlocks().size(),
            "Both prompt blocks must be merged into the single user message");
    }

    @Test
    void timestampFromPromptEntryIsParsedToEpochMillis() {
        String isoTs = "2024-06-15T12:00:00Z";
        EntryData.Prompt p = new EntryData.Prompt("Hello", isoTs, null, "", "entry-id");
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(List.of(p));

        assertEquals(1, messages.size());
        assertEquals(Instant.parse(isoTs).toEpochMilli(), messages.get(0).createdAt());
    }

    @Test
    void modelFromTextEntryIsPropagatedCorrectly() {
        EntryData.Text t = new EntryData.Text("Response", Instant.now().toString(), "", "gpt-4o", "");
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Q"), t));

        assertEquals(2, messages.size());
        assertEquals("gpt-4o", messages.get(1).model());
    }

    @Test
    void invalidTimestampDefaultsToZero() {
        EntryData.Prompt p = new EntryData.Prompt("Hello", "not-a-date", null, "", "");
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(List.of(p));
        assertEquals(0L, messages.get(0).createdAt());
    }

    // ── AnthropicMessage.toJsonLine ──────────────────────────────────────────

    @Test
    void toJsonLineProducesValidJsonWithRoleAndContent() {
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Hello"), text("Hi!")));

        for (AnthropicMessage msg : messages) {
            String line = msg.toJsonLine();
            assertDoesNotThrow(() -> JsonParser.parseString(line),
                "toJsonLine() must produce valid JSON");
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            assertTrue(obj.has("role"), "JSON line must have 'role'");
            assertTrue(obj.has("content"), "JSON line must have 'content'");
            assertTrue(obj.get("content").isJsonArray(), "'content' must be a JsonArray");
        }
    }

    @Test
    void toJsonLineDoesNotHtmlEscapeSpecialChars() {
        // Anthropic's API sends raw JSON in tool results — HTML-escaping '<', '>' breaks it
        List<AnthropicMessage> messages = AnthropicClientExporter.toAnthropicMessages(
            List.of(prompt("Use <b> tags")));
        String line = messages.get(0).toJsonLine();
        assertFalse(line.contains("\\u003c"),
            "toJsonLine() must not HTML-escape '<'; check disableHtmlEscaping() on Gson");
    }

    // ── trimToSizeBudget ─────────────────────────────────────────────────────

    @Test
    void trimToSizeBudgetZeroIsNoOp() {
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(prompt("Hello"), text("World"))));
        int originalSize = messages.size();
        AnthropicClientExporter.trimToSizeBudget(messages, 0);
        assertEquals(originalSize, messages.size(), "Budget=0 must not trim anything");
    }

    @Test
    void trimToSizeBudgetNegativeIsNoOp() {
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(prompt("Hello"), text("World"))));
        int originalSize = messages.size();
        AnthropicClientExporter.trimToSizeBudget(messages, -100);
        assertEquals(originalSize, messages.size(), "Negative budget must not trim anything");
    }

    @Test
    void trimToSizeBudgetLargeEnoughPreservesAllMessages() {
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(prompt("Hello"), text("World"))));
        int originalSize = messages.size();
        AnthropicClientExporter.trimToSizeBudget(messages, Integer.MAX_VALUE);
        assertEquals(originalSize, messages.size(), "Budget=MAX_INT must preserve all messages");
    }

    @Test
    void trimToSizeBudgetDropsMessagesWhenOverLimit() {
        // Build a multi-turn conversation so there is something to trim
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(
                    prompt("First question?"),
                    text("First long answer that occupies several chars in the JSON serialization."),
                    prompt("Second question?"),
                    text("Second long answer that also occupies several chars.")
                )));
        int originalSize = messages.size();
        // Force aggressive trimming with a 1-char budget
        AnthropicClientExporter.trimToSizeBudget(messages, 1);
        assertTrue(messages.size() < originalSize,
            "Tiny budget must have dropped at least one message; original=" + originalSize
                + " after=" + messages.size());
    }

    @Test
    void trimToSizeBudgetResultFitsWithinBudget() {
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(
                    prompt("Turn 1 question"),
                    text("Turn 1 answer"),
                    prompt("Turn 2 question"),
                    text("Turn 2 answer")
                )));
        // Choose a budget that can hold only ~1 message
        int budget = 100;
        AnthropicClientExporter.trimToSizeBudget(messages, budget);

        int totalChars = messages.stream().mapToInt(m -> m.toJsonLine().length()).sum();
        assertTrue(totalChars <= budget || messages.isEmpty(),
            "After trimming, total chars (" + totalChars + ") should be within budget (" + budget + ")");
    }

    @Test
    void trimToSizeBudgetPreservesFirstMessageIsUser() {
        List<AnthropicMessage> messages = new ArrayList<>(
            AnthropicClientExporter.toAnthropicMessages(
                List.of(
                    prompt("Q1"), text("A1"),
                    prompt("Q2"), text("A2"),
                    prompt("Q3"), text("A3")
                )));
        AnthropicClientExporter.trimToSizeBudget(messages, 200);

        if (!messages.isEmpty()) {
            assertEquals("user", messages.get(0).role(),
                "After trimming, the first remaining message must be 'user' to keep valid alternation");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static EntryData.Prompt prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text text(String content) {
        return new EntryData.Text(content, Instant.now().toString(), "", "", "");
    }

    private static EntryData.ToolCall toolCall(String name, String args, String result) {
        return new EntryData.ToolCall(name, args, "other", result);
    }
}
