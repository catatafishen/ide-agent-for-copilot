package com.github.catatafishen.agentbridge.session.exporters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the package-private pure static helpers in {@link KiroClientExporter}:
 * {@code mergeConsecutivePrompts}, {@code mergeConsecutiveAssistantMessages},
 * {@code textContentBlock}, and {@code wrapMessage}.
 */
class KiroClientExporterStaticMethodsTest {

    // ── textContentBlock ─────────────────────────────────────────────────────

    @Test
    void textContentBlock_hasKindText() {
        JsonObject block = KiroClientExporter.textContentBlock("hello");
        assertEquals("text", block.get("kind").getAsString());
    }

    @Test
    void textContentBlock_hasDataField() {
        JsonObject block = KiroClientExporter.textContentBlock("hello world");
        assertEquals("hello world", block.get("data").getAsString());
    }

    @Test
    void textContentBlock_emptyString() {
        JsonObject block = KiroClientExporter.textContentBlock("");
        assertEquals("", block.get("data").getAsString());
    }

    @Test
    void textContentBlock_specialCharacters() {
        String special = "line1\nline2\ttab \"quotes\" 'apos'";
        JsonObject block = KiroClientExporter.textContentBlock(special);
        assertEquals(special, block.get("data").getAsString());
    }

    // ── wrapMessage ──────────────────────────────────────────────────────────

    @Test
    void wrapMessage_hasVersionField() {
        JsonArray content = new JsonArray();
        JsonObject msg = KiroClientExporter.wrapMessage("Prompt", "msg-1", content);
        assertEquals("v1", msg.get("version").getAsString());
    }

    @Test
    void wrapMessage_hasKindField() {
        JsonArray content = new JsonArray();
        JsonObject msg = KiroClientExporter.wrapMessage("AssistantMessage", "msg-2", content);
        assertEquals("AssistantMessage", msg.get("kind").getAsString());
    }

    @Test
    void wrapMessage_hasDataWithMessageIdAndContent() {
        JsonArray content = new JsonArray();
        content.add(KiroClientExporter.textContentBlock("hello"));

        JsonObject msg = KiroClientExporter.wrapMessage("Prompt", "msg-42", content);
        JsonObject data = msg.getAsJsonObject("data");
        assertEquals("msg-42", data.get("message_id").getAsString());
        assertTrue(data.has("content"));
        assertEquals(1, data.getAsJsonArray("content").size());
    }

    @Test
    void wrapMessage_emptyContent() {
        JsonArray content = new JsonArray();
        JsonObject msg = KiroClientExporter.wrapMessage("Prompt", "msg-x", content);
        assertEquals(0, msg.getAsJsonObject("data").getAsJsonArray("content").size());
    }

    @Test
    void wrapMessage_contentPassedThrough() {
        JsonArray content = new JsonArray();
        JsonObject block1 = KiroClientExporter.textContentBlock("a");
        JsonObject block2 = KiroClientExporter.textContentBlock("b");
        content.add(block1);
        content.add(block2);

        JsonObject msg = KiroClientExporter.wrapMessage("AssistantMessage", "msg-y", content);
        JsonArray resultContent = msg.getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, resultContent.size());
        assertEquals("a", resultContent.get(0).getAsJsonObject().get("data").getAsString());
        assertEquals("b", resultContent.get(1).getAsJsonObject().get("data").getAsString());
    }

    // ── mergeConsecutivePrompts ───────────────────────────────────────────────

    @Test
    void mergeConsecutivePrompts_noPrompts_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("AssistantMessage", "a"));
        KiroClientExporter.mergeConsecutivePrompts(messages);
        assertEquals(1, messages.size());
    }

    @Test
    void mergeConsecutivePrompts_singlePrompt_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "hello"));
        KiroClientExporter.mergeConsecutivePrompts(messages);
        assertEquals(1, messages.size());
    }

    @Test
    void mergeConsecutivePrompts_nonConsecutivePrompts_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q1"));
        messages.add(kiroMsg("AssistantMessage", "A1"));
        messages.add(kiroMsg("Prompt", "Q2"));

        KiroClientExporter.mergeConsecutivePrompts(messages);
        assertEquals(3, messages.size());
    }

    @Test
    void mergeConsecutivePrompts_twoConsecutive_keepsLast() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "first"));
        messages.add(kiroMsg("Prompt", "second"));
        messages.add(kiroMsg("AssistantMessage", "reply"));

        KiroClientExporter.mergeConsecutivePrompts(messages);

        assertEquals(2, messages.size());
        assertEquals("Prompt", messages.get(0).get("kind").getAsString());
        // The kept prompt should be "second" (the later one)
        String keptText = messages.get(0).getAsJsonObject("data")
            .getAsJsonArray("content").get(0).getAsJsonObject()
            .get("data").getAsString();
        assertEquals("second", keptText);
    }

    @Test
    void mergeConsecutivePrompts_threeConsecutive_keepsOnlyLast() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "first"));
        messages.add(kiroMsg("Prompt", "second"));
        messages.add(kiroMsg("Prompt", "third"));
        messages.add(kiroMsg("AssistantMessage", "reply"));

        KiroClientExporter.mergeConsecutivePrompts(messages);

        assertEquals(2, messages.size());
        String keptText = messages.get(0).getAsJsonObject("data")
            .getAsJsonArray("content").get(0).getAsJsonObject()
            .get("data").getAsString();
        assertEquals("third", keptText);
    }

    @Test
    void mergeConsecutivePrompts_duplicatesInMiddle() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q1"));
        messages.add(kiroMsg("AssistantMessage", "A1"));
        messages.add(kiroMsg("Prompt", "Q2a"));
        messages.add(kiroMsg("Prompt", "Q2b"));
        messages.add(kiroMsg("AssistantMessage", "A2"));

        KiroClientExporter.mergeConsecutivePrompts(messages);

        assertEquals(4, messages.size());
        assertEquals("Prompt", messages.get(0).get("kind").getAsString());
        assertEquals("AssistantMessage", messages.get(1).get("kind").getAsString());
        assertEquals("Prompt", messages.get(2).get("kind").getAsString());
        assertEquals("AssistantMessage", messages.get(3).get("kind").getAsString());

        String keptText = messages.get(2).getAsJsonObject("data")
            .getAsJsonArray("content").get(0).getAsJsonObject()
            .get("data").getAsString();
        assertEquals("Q2b", keptText);
    }

    // ── mergeConsecutiveAssistantMessages ─────────────────────────────────────

    @Test
    void mergeConsecutiveAssistantMessages_noAssistants_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q"));
        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);
        assertEquals(1, messages.size());
    }

    @Test
    void mergeConsecutiveAssistantMessages_singleAssistant_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q"));
        messages.add(kiroMsg("AssistantMessage", "A"));
        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);
        assertEquals(2, messages.size());
    }

    @Test
    void mergeConsecutiveAssistantMessages_nonConsecutive_noOp() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q1"));
        messages.add(kiroMsg("AssistantMessage", "A1"));
        messages.add(kiroMsg("Prompt", "Q2"));
        messages.add(kiroMsg("AssistantMessage", "A2"));

        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);
        assertEquals(4, messages.size());
    }

    @Test
    void mergeConsecutiveAssistantMessages_twoConsecutive_mergesContentBlocks() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q"));
        messages.add(kiroMsg("AssistantMessage", "Part1"));
        messages.add(kiroMsg("AssistantMessage", "Part2"));

        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);

        assertEquals(2, messages.size());
        assertEquals("Prompt", messages.get(0).get("kind").getAsString());
        assertEquals("AssistantMessage", messages.get(1).get("kind").getAsString());

        JsonArray mergedContent = messages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, mergedContent.size());
        assertEquals("Part1", mergedContent.get(0).getAsJsonObject().get("data").getAsString());
        assertEquals("Part2", mergedContent.get(1).getAsJsonObject().get("data").getAsString());
    }

    @Test
    void mergeConsecutiveAssistantMessages_threeConsecutive_allMerged() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q"));
        messages.add(kiroMsg("AssistantMessage", "A"));
        messages.add(kiroMsg("AssistantMessage", "B"));
        messages.add(kiroMsg("AssistantMessage", "C"));

        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);

        assertEquals(2, messages.size());
        JsonArray mergedContent = messages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(3, mergedContent.size());
    }

    @Test
    void mergeConsecutiveAssistantMessages_toolResultsBetween_notMerged() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q"));
        messages.add(kiroMsg("AssistantMessage", "A1"));
        messages.add(kiroMsg("ToolResults", "tools"));
        messages.add(kiroMsg("AssistantMessage", "A2"));

        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);
        assertEquals(4, messages.size(), "ToolResults between assistants should prevent merging");
    }

    @Test
    void mergeConsecutiveAssistantMessages_duplicatesInMiddle() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(kiroMsg("Prompt", "Q1"));
        messages.add(kiroMsg("AssistantMessage", "A1a"));
        messages.add(kiroMsg("AssistantMessage", "A1b"));
        messages.add(kiroMsg("Prompt", "Q2"));
        messages.add(kiroMsg("AssistantMessage", "A2"));

        KiroClientExporter.mergeConsecutiveAssistantMessages(messages);

        assertEquals(4, messages.size());
        // First assistant pair merged
        JsonArray merged = messages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, merged.size());
        // Second assistant not merged (it's alone)
        JsonArray single = messages.get(3).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, single.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a minimal Kiro-format message with a single text content block.
     */
    private static JsonObject kiroMsg(String kind, String textContent) {
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("kind", "text");
        block.addProperty("data", textContent);
        content.add(block);

        JsonObject data = new JsonObject();
        data.addProperty("message_id", "test-" + System.nanoTime());
        data.add("content", content);

        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", "v1");
        envelope.addProperty("kind", kind);
        envelope.add("data", data);
        return envelope;
    }
}
