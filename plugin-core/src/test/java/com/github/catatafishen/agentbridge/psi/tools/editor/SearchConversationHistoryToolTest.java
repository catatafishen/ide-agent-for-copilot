package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure static methods in {@link SearchConversationHistoryTool}.
 * These methods handle filtering, formatting, and turn-based pagination of conversation entries.
 */
class SearchConversationHistoryToolTest {

    // ── parseTurnNumber ─────────────────────────────────────

    @Test
    void parseTurnNumber_parsesPrefixed() throws Exception {
        assertEquals(3, invokeParseTurnNumber("t3"));
    }

    @Test
    void parseTurnNumber_parsesUpperCase() throws Exception {
        assertEquals(5, invokeParseTurnNumber("T5"));
    }

    @Test
    void parseTurnNumber_parsesNumericOnly() throws Exception {
        assertEquals(7, invokeParseTurnNumber("7"));
    }

    @Test
    void parseTurnNumber_trimsWhitespace() throws Exception {
        assertEquals(2, invokeParseTurnNumber("  t2  "));
    }

    @Test
    void parseTurnNumber_returnsNegativeForInvalid() throws Exception {
        assertEquals(-1, invokeParseTurnNumber("abc"));
    }

    @Test
    void parseTurnNumber_returnsNegativeForEmpty() throws Exception {
        assertEquals(-1, invokeParseTurnNumber("t"));
    }

    // ── isMatchingEntry ─────────────────────────────────────

    @Test
    void isMatchingEntry_matchesCaseInsensitive() throws Exception {
        assertTrue(invokeIsMatchingEntry("Hello World", "hello"));
    }

    @Test
    void isMatchingEntry_nullQueryMatchesAll() throws Exception {
        assertTrue(invokeIsMatchingEntry("anything", null));
    }

    @Test
    void isMatchingEntry_nullLineReturnsFalse() throws Exception {
        assertFalse(invokeIsMatchingEntry(null, "query"));
    }

    @Test
    void isMatchingEntry_emptyLineReturnsFalse() throws Exception {
        assertFalse(invokeIsMatchingEntry("", "query"));
    }

    @Test
    void isMatchingEntry_noMatchReturnsFalse() throws Exception {
        assertFalse(invokeIsMatchingEntry("hello world", "xyz"));
    }

    // ── isWithinTimeRange ───────────────────────────────────

    @Test
    void isWithinTimeRange_emptyTimestampAlwaysIncluded() throws Exception {
        EntryData.Text entry = new EntryData.Text("text", "");
        assertTrue(invokeIsWithinTimeRange(entry, Instant.now().minusSeconds(60), Instant.now()));
    }

    @Test
    void isWithinTimeRange_withinRange() throws Exception {
        Instant now = Instant.parse("2025-06-15T12:00:00Z");
        EntryData.Prompt entry = new EntryData.Prompt("hi", "2025-06-15T12:00:00Z");
        assertTrue(invokeIsWithinTimeRange(entry,
            now.minusSeconds(60), now.plusSeconds(60)));
    }

    @Test
    void isWithinTimeRange_beforeSinceExcluded() throws Exception {
        Instant since = Instant.parse("2025-06-15T12:00:00Z");
        EntryData.Prompt entry = new EntryData.Prompt("hi", "2025-06-15T11:00:00Z");
        assertFalse(invokeIsWithinTimeRange(entry, since, null));
    }

    @Test
    void isWithinTimeRange_afterUntilExcluded() throws Exception {
        Instant until = Instant.parse("2025-06-15T12:00:00Z");
        EntryData.Prompt entry = new EntryData.Prompt("hi", "2025-06-15T13:00:00Z");
        assertFalse(invokeIsWithinTimeRange(entry, null, until));
    }

    @Test
    void isWithinTimeRange_nullBoundsIncludesAll() throws Exception {
        EntryData.Prompt entry = new EntryData.Prompt("hi", "2025-06-15T12:00:00Z");
        assertTrue(invokeIsWithinTimeRange(entry, null, null));
    }

    // ── formatConversationEntry ──────────────────────────────

    @Test
    void formatConversationEntry_prompt() throws Exception {
        EntryData.Prompt entry = new EntryData.Prompt("What is 2+2?", "2025-06-15T12:00:00Z");
        String result = invokeFormatConversationEntry(entry);
        assertNotNull(result);
        assertTrue(result.startsWith(">>> What is 2+2?"), "Prompt should start with >>>");
    }

    @Test
    void formatConversationEntry_text() throws Exception {
        EntryData.Text entry = new EntryData.Text("The answer is 4.");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("The answer is 4.", result);
    }

    @Test
    void formatConversationEntry_thinking() throws Exception {
        EntryData.Thinking entry = new EntryData.Thinking("reasoning about math");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("[thinking] reasoning about math", result);
    }

    @Test
    void formatConversationEntry_emptyThinkingReturnsNull() throws Exception {
        EntryData.Thinking entry = new EntryData.Thinking("  ");
        assertNull(invokeFormatConversationEntry(entry));
    }

    @Test
    void formatConversationEntry_toolCall() throws Exception {
        EntryData.ToolCall entry = new EntryData.ToolCall("read_file", "/src/Main.java");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("read_file /src/Main.java", result);
    }

    @Test
    void formatConversationEntry_toolCallNoArgs() throws Exception {
        EntryData.ToolCall entry = new EntryData.ToolCall("git_status");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("git_status", result);
    }

    @Test
    void formatConversationEntry_subAgent() throws Exception {
        EntryData.SubAgent entry = new EntryData.SubAgent("explore", "Find test files");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("SubAgent: explore — Find test files", result);
    }

    @Test
    void formatConversationEntry_contextFiles() throws Exception {
        EntryData.ContextFiles entry = new EntryData.ContextFiles(Collections.emptyList());
        String result = invokeFormatConversationEntry(entry);
        assertEquals("Context files attached", result);
    }

    @Test
    void formatConversationEntry_statusWithMessage() throws Exception {
        EntryData.Status entry = new EntryData.Status("⏳", "Working...");
        String result = invokeFormatConversationEntry(entry);
        assertEquals("Status: Working...", result);
    }

    @Test
    void formatConversationEntry_statusEmptyReturnsNull() throws Exception {
        EntryData.Status entry = new EntryData.Status("⏳", "");
        assertNull(invokeFormatConversationEntry(entry));
    }

    // ── filterByTurns ───────────────────────────────────────

    @Test
    void filterByTurns_noFiltersReturnsAll() throws Exception {
        List<EntryData> entries = buildConversation();
        Object options = createFilterOptions(null, null, null, null, null, null, 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertEquals(entries.size(), result.size());
    }

    @Test
    void filterByTurns_lastNSelectsLastTurns() throws Exception {
        List<EntryData> entries = buildConversation();
        // 3 prompts: t1=[0..1], t2=[2..3], t3=[4..5]. lastN=1 → last prompt + its response
        Object options = createFilterOptions(null, null, null, 1, null, null, 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertEquals(2, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.getFirst());
        assertEquals("Question 3", ((EntryData.Prompt) result.getFirst()).getText());
    }

    @Test
    void filterByTurns_lastNWithOffset() throws Exception {
        List<EntryData> entries = buildConversation();
        // lastN=1, offset=1 → skip last prompt, get second-to-last
        Object options = createFilterOptions(null, null, null, 1, 1, null, 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertEquals(2, result.size());
        assertEquals("Question 2", ((EntryData.Prompt) result.getFirst()).getText());
    }

    @Test
    void filterByTurns_offsetBeyondSizeReturnsEmpty() throws Exception {
        List<EntryData> entries = buildConversation();
        Object options = createFilterOptions(null, null, null, 1, 100, null, 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertTrue(result.isEmpty());
    }

    // ── filterByTurnId ──────────────────────────────────────

    @Test
    void filterByTurnId_selectsSpecificTurn() throws Exception {
        List<EntryData> entries = buildConversation();
        Object options = createFilterOptions(null, null, null, null, null, "t2", 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertEquals(2, result.size());
        assertEquals("Question 2", ((EntryData.Prompt) result.getFirst()).getText());
    }

    @Test
    void filterByTurnId_outOfRangeReturnsEmpty() throws Exception {
        List<EntryData> entries = buildConversation();
        Object options = createFilterOptions(null, null, null, null, null, "t99", 8000);
        List<EntryData> result = invokeFilterByTurns(entries, options);
        assertTrue(result.isEmpty());
    }

    // ── formatEpochMillis ───────────────────────────────────

    @Test
    void formatEpochMillis_zeroReturnsUnknown() throws Exception {
        assertEquals("unknown", invokeFormatEpochMillis(0));
    }

    @Test
    void formatEpochMillis_negativeReturnsUnknown() throws Exception {
        assertEquals("unknown", invokeFormatEpochMillis(-1));
    }

    @Test
    void formatEpochMillis_validTimestampFormatsCorrectly() throws Exception {
        // 2025-01-01 00:00:00 UTC = 1735689600000
        String result = invokeFormatEpochMillis(1735689600000L);
        assertNotNull(result);
        assertNotEquals("unknown", result);
        assertTrue(result.contains("2025"), "Should contain year 2025");
    }

    // ── formatTimestamp ─────────────────────────────────────

    @Test
    void formatTimestamp_validIso8601FormatsCorrectly() throws Exception {
        String result = invokeFormatTimestamp("2025-06-15T12:00:00Z");
        assertNotNull(result);
        assertTrue(result.contains("2025"), "Should contain year 2025");
        assertFalse(result.contains("T"), "Should not contain ISO 'T' separator");
        assertFalse(result.contains("Z"), "Should not contain 'Z' suffix");
    }

    @Test
    void formatTimestamp_invalidStringReturnsInputAsIs() throws Exception {
        assertEquals("not-a-date", invokeFormatTimestamp("not-a-date"));
    }

    @Test
    void formatTimestamp_emptyStringReturnsEmpty() throws Exception {
        assertEquals("", invokeFormatTimestamp(""));
    }

    @Test
    void formatTimestamp_partialIsoReturnsInputAsIs() throws Exception {
        // "2025-06-15" is not a valid Instant, so it should fall through to the catch
        String result = invokeFormatTimestamp("2025-06-15");
        assertEquals("2025-06-15", result, "Partial ISO should be returned as-is");
    }

    // ── formatAndFilterEntries (truncation) ─────────────────

    @Test
    void formatAndFilterEntries_truncatesAtMaxChars() throws Exception {
        List<EntryData> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(new EntryData.Text("Line number " + i + " with some extra text to fill space"));
        }
        Object options = createFilterOptions(null, null, null, null, null, null, 100);
        String result = invokeFormatAndFilterEntries(entries, options);
        assertTrue(result.contains("truncated"), "Should contain truncation marker");
        assertTrue(result.contains("100"), "Should mention maxChars limit");
    }

    @Test
    void formatAndFilterEntries_noTruncationWhenUnderLimit() throws Exception {
        List<EntryData> entries = List.of(new EntryData.Text("Short text"));
        Object options = createFilterOptions(null, null, null, null, null, null, 8000);
        String result = invokeFormatAndFilterEntries(entries, options);
        assertFalse(result.contains("truncated"), "Should not truncate short content");
        assertTrue(result.contains("Short text"));
    }

    @Test
    void formatAndFilterEntries_queryFiltersEntries() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Text("apple pie"),
            new EntryData.Text("banana split"),
            new EntryData.Text("apple sauce")
        );
        Object options = createFilterOptions("apple", null, null, null, null, null, 8000);
        String result = invokeFormatAndFilterEntries(entries, options);
        assertTrue(result.contains("apple pie"), "Should include matching entries");
        assertTrue(result.contains("apple sauce"), "Should include matching entries");
        assertFalse(result.contains("banana"), "Should exclude non-matching entries");
    }

    @Test
    void formatAndFilterEntries_emptyEntriesReturnsEmpty() throws Exception {
        Object options = createFilterOptions(null, null, null, null, null, null, 8000);
        String result = invokeFormatAndFilterEntries(Collections.emptyList(), options);
        assertTrue(result.isEmpty(), "Empty entries should produce empty output");
    }

    @Test
    void formatAndFilterEntries_thinkingEntriesWithBlankContentSkipped() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Thinking("  "),
            new EntryData.Text("visible text")
        );
        Object options = createFilterOptions(null, null, null, null, null, null, 8000);
        String result = invokeFormatAndFilterEntries(entries, options);
        assertFalse(result.contains("[thinking]"), "Blank thinking should be skipped");
        assertTrue(result.contains("visible text"));
    }

    // ── Helpers: build test data ────────────────────────────

    /**
     * Builds a 3-turn conversation: [Prompt, Text, Prompt, Text, Prompt, Text]
     */
    private List<EntryData> buildConversation() {
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("Question 1", "2025-06-15T10:00:00Z"));
        entries.add(new EntryData.Text("Answer 1", "2025-06-15T10:01:00Z"));
        entries.add(new EntryData.Prompt("Question 2", "2025-06-15T11:00:00Z"));
        entries.add(new EntryData.Text("Answer 2", "2025-06-15T11:01:00Z"));
        entries.add(new EntryData.Prompt("Question 3", "2025-06-15T12:00:00Z"));
        entries.add(new EntryData.Text("Answer 3", "2025-06-15T12:01:00Z"));
        return entries;
    }

    // ── Reflection helpers ──────────────────────────────────

    private static int invokeParseTurnNumber(String turnId) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("parseTurnNumber", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, turnId);
    }

    private static boolean invokeIsMatchingEntry(String line, String query) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("isMatchingEntry", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, line, query);
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean invokeIsWithinTimeRange(EntryData entry, Instant since, Instant until) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod(
            "isWithinTimeRange", EntryData.class, Instant.class, Instant.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, entry, since, until);
    }

    private static String invokeFormatConversationEntry(EntryData entry) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("formatConversationEntry", EntryData.class);
        m.setAccessible(true);
        return (String) m.invoke(null, entry);
    }

    @SuppressWarnings("unchecked")
    private static List<EntryData> invokeFilterByTurns(List<EntryData> entries, Object options) throws Exception {
        Class<?> filterOptionsClass = Class.forName(
            SearchConversationHistoryTool.class.getName() + "$FilterOptions");
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("filterByTurns", List.class, filterOptionsClass);
        m.setAccessible(true);
        return (List<EntryData>) m.invoke(null, entries, options);
    }

    private static String invokeFormatEpochMillis(long epochMillis) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("formatEpochMillis", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, epochMillis);
    }

    private static String invokeFormatTimestamp(String ts) throws Exception {
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod("formatTimestamp", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, ts);
    }

    private static String invokeFormatAndFilterEntries(List<EntryData> entries, Object options) throws Exception {
        Class<?> filterOptionsClass = Class.forName(
            SearchConversationHistoryTool.class.getName() + "$FilterOptions");
        Method m = SearchConversationHistoryTool.class.getDeclaredMethod(
            "formatAndFilterEntries", List.class, filterOptionsClass);
        m.setAccessible(true);
        return (String) m.invoke(null, entries, options);
    }

    private static Object createFilterOptions(String query, Instant since, Instant until,
                                              Integer lastN, Integer offset, String turnId,
                                              int maxChars) throws Exception {
        Class<?> cls = Class.forName(SearchConversationHistoryTool.class.getName() + "$FilterOptions");
        Constructor<?> ctor = cls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(query, since, until, lastN, offset, turnId, maxChars);
    }
}
