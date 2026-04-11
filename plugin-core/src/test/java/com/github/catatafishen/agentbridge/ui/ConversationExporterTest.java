package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationExporterTest {

    // ── getConversationText ─────────────────────────────────────────────

    @Test
    void getConversationText_empty() {
        var exporter = new ConversationExporter(List.of());
        assertEquals("", exporter.getConversationText());
    }

    @Test
    void getConversationText_promptEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Prompt("Hello world", "")
        ));
        String result = exporter.getConversationText();
        assertTrue(result.contains(">>> Hello world"));
    }

    @Test
    void getConversationText_textEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Text("The answer is 42")
        ));
        String result = exporter.getConversationText();
        assertTrue(result.contains("The answer is 42"));
    }

    @Test
    void getConversationText_thinkingEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Thinking("Let me think...")
        ));
        assertTrue(exporter.getConversationText().contains("[thinking] Let me think..."));
    }

    @Test
    void getConversationText_toolCallEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.ToolCall("read_file", "{\"path\":\"test.txt\"}")
        ));
        String result = exporter.getConversationText();
        assertTrue(result.contains("\uD83D\uDD27")); // wrench emoji
        assertTrue(result.contains("test.txt"));
    }

    @Test
    void getConversationText_statusEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Status("\u2705", "Build successful")
        ));
        assertTrue(exporter.getConversationText().contains("\u2705 Build successful"));
    }

    @Test
    void getConversationText_contextFilesEntry() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.ContextFiles(List.of(
                new FileRef("Foo.java", "/src/Foo.java")
            ))
        ));
        String result = exporter.getConversationText();
        assertTrue(result.contains("1 context file(s)"));
        assertTrue(result.contains("Foo.java"));
    }

    @Test
    void getConversationText_mixedEntries() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Prompt("What is 2+2?", ""),
            new EntryData.Text("The answer is 4.")
        ));
        String result = exporter.getConversationText();
        assertTrue(result.contains(">>> What is 2+2?"));
        assertTrue(result.contains("The answer is 4."));
    }

    @Test
    void getConversationText_turnStatsSkipped() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.TurnStats("t1")
        ));
        assertEquals("", exporter.getConversationText().trim());
    }

    // ── getCompressedSummary ────────────────────────────────────────────

    @Test
    void getCompressedSummary_empty() {
        assertEquals("", new ConversationExporter(List.of()).getCompressedSummary(8000));
    }

    @Test
    void getCompressedSummary_singleTurn() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Prompt("Hello", ""),
            new EntryData.Text("Hi there!")
        ));
        String result = exporter.getCompressedSummary(8000);
        assertTrue(result.contains("[Previous conversation: 1 turn."));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("Hi there!"));
    }

    @Test
    void getCompressedSummary_multipleTurns() {
        var entries = new java.util.ArrayList<EntryData>();
        for (int i = 1; i <= 5; i++) {
            entries.add(new EntryData.Prompt("Question " + i, ""));
            entries.add(new EntryData.Text("Answer " + i));
        }
        var exporter = new ConversationExporter(entries);
        String result = exporter.getCompressedSummary(8000);
        assertTrue(result.contains("[Previous conversation: 5 turns."));
    }

    @Test
    void getCompressedSummary_respectsBudget() {
        var entries = new java.util.ArrayList<EntryData>();
        for (int i = 0; i < 20; i++) {
            entries.add(new EntryData.Prompt("Long question " + "x".repeat(200) + i, ""));
            entries.add(new EntryData.Text("Long answer " + "y".repeat(200) + i));
        }
        var exporter = new ConversationExporter(entries);
        String result = exporter.getCompressedSummary(2000);
        assertTrue(result.length() <= 3000); // header + budget with some slack
        assertTrue(result.contains("Showing")); // indicates truncation
    }

    @Test
    void getCompressedSummary_toolCallsCounted() {
        var exporter = new ConversationExporter(List.of(
            new EntryData.Prompt("Do something", ""),
            new EntryData.ToolCall("read_file"),
            new EntryData.ToolCall("search_text"),
            new EntryData.Text("Done.")
        ));
        String result = exporter.getCompressedSummary(8000);
        assertTrue(result.contains("tool call"));
    }
}
