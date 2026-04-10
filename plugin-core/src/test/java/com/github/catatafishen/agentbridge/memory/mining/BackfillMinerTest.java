package com.github.catatafishen.agentbridge.memory.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BackfillMiner.BackfillResult} and basic BackfillMiner invariants.
 * Full integration tests require the IntelliJ platform (SessionStoreV2, MemoryService).
 */
class BackfillMinerTest {

    @Test
    void backfillResultRecordFields() {
        BackfillMiner.BackfillResult result = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        assertEquals(5, result.sessions());
        assertEquals(20, result.stored());
        assertEquals(3, result.filtered());
        assertEquals(2, result.duplicates());
        assertEquals(30, result.exchanges());
    }

    @Test
    void backfillResultEquality() {
        BackfillMiner.BackfillResult a = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        BackfillMiner.BackfillResult b = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void backfillResultInequality() {
        BackfillMiner.BackfillResult a = new BackfillMiner.BackfillResult(5, 20, 3, 2, 30);
        BackfillMiner.BackfillResult b = new BackfillMiner.BackfillResult(5, 21, 3, 2, 30);
        assertNotEquals(a, b);
    }

    @Test
    void emptyBackfillResult() {
        BackfillMiner.BackfillResult empty = new BackfillMiner.BackfillResult(0, 0, 0, 0, 0);
        assertEquals(0, empty.sessions());
        assertEquals(0, empty.stored());
        assertEquals(0, empty.filtered());
        assertEquals(0, empty.duplicates());
        assertEquals(0, empty.exchanges());
    }

    @Test
    void backfillResultToStringContainsFields() {
        BackfillMiner.BackfillResult result = new BackfillMiner.BackfillResult(3, 10, 2, 1, 15);
        String str = result.toString();
        assertTrue(str.contains("3"));
        assertTrue(str.contains("10"));
        assertTrue(str.contains("15"));
    }
}
