package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link RoomDetector} and {@link MemoryClassifier} — keyword/regex-based
 * room detection and memory type classification.
 */
class MemoryMiningTest {

    // =====================================================================
    // RoomDetector — keyword-based contains() scoring on lowercased text
    // =====================================================================

    @Test
    void roomDetector_codebase() {
        assertEquals(DrawerDocument.ROOM_CODEBASE,
            RoomDetector.detect("The class has a method and an interface"));
    }

    @Test
    void roomDetector_debugging() {
        assertEquals(DrawerDocument.ROOM_DEBUGGING,
            RoomDetector.detect("There is a bug causing a crash with an exception"));
    }

    @Test
    void roomDetector_workflow() {
        assertEquals(DrawerDocument.ROOM_WORKFLOW,
            RoomDetector.detect("Run the gradle build and deploy to staging"));
    }

    @Test
    void roomDetector_decisions() {
        assertEquals(DrawerDocument.ROOM_DECISIONS,
            RoomDetector.detect("The decision involved a trade-off instead of the alternative"));
    }

    @Test
    void roomDetector_preferences() {
        assertEquals(DrawerDocument.ROOM_PREFERENCES,
            RoomDetector.detect("I prefer this naming convention for consistency"));
    }

    @Test
    void roomDetector_general() {
        assertEquals(DrawerDocument.ROOM_GENERAL,
            RoomDetector.detect("Hello, how are you doing today?"));
    }

    @Test
    void roomDetector_caseInsensitive() {
        // RoomDetector lowercases text before contains() checks
        assertEquals(DrawerDocument.ROOM_CODEBASE,
            RoomDetector.detect("CLASS METHOD INTERFACE"));
    }

    @Test
    void roomDetector_highestScoreWins() {
        // 4 debugging keywords (bug, crash, error, exception) vs 1 codebase keyword (class)
        String text = "The bug causes a crash with an error exception. The class needs fixing.";
        assertEquals(DrawerDocument.ROOM_DEBUGGING, RoomDetector.detect(text));
    }

    // =====================================================================
    // MemoryClassifier — regex word-boundary (\b) scoring with find()
    // =====================================================================

    @Test
    void classifier_decision() {
        // Matches: "decided to", "instead of", "going with"
        assertEquals(DrawerDocument.TYPE_DECISION,
            MemoryClassifier.classify("We decided to use this instead of that, going with the new approach"));
    }

    @Test
    void classifier_problem() {
        // Matches: "bug", "failing", "broken"
        assertEquals(DrawerDocument.TYPE_PROBLEM,
            MemoryClassifier.classify("There is a bug and the tests are failing, the feature is broken"));
    }

    @Test
    void classifier_solution() {
        // Matches: "fixed", "resolved", "the fix"
        assertEquals(DrawerDocument.TYPE_SOLUTION,
            MemoryClassifier.classify("We fixed the issue and resolved it, the fix was straightforward"));
    }

    @Test
    void classifier_context() {
        // Matches: "architecture", "pattern", "interface"
        assertEquals(DrawerDocument.TYPE_CONTEXT,
            MemoryClassifier.classify("The architecture uses a pattern with an interface for abstraction"));
    }

    @Test
    void classifier_general() {
        // No type markers match → falls back to general
        assertEquals(DrawerDocument.TYPE_GENERAL,
            MemoryClassifier.classify("Hello, let me help you with your question today"));
    }

    @Test
    void classifier_caseInsensitive() {
        // Patterns use Pattern.CASE_INSENSITIVE; text is also lowercased
        assertEquals(DrawerDocument.TYPE_DECISION,
            MemoryClassifier.classify("WE DECIDED TO GO WITH THIS"));
    }

    @Test
    void classifier_wordBoundary() {
        // \bbug\b does NOT match inside "debugging" — word boundary before 'b'
        // fails because 'e' (a word char) precedes 'b'.
        // No other problem/decision/solution/context markers present → general
        assertEquals(DrawerDocument.TYPE_GENERAL,
            MemoryClassifier.classify("I was debugging the application all day"));
    }

    @Test
    void classifier_highestScoreWins() {
        // 4 problem markers (bug, broken, failing, crash) vs 1 solution marker (fixed)
        String text = "There is a bug and it's broken, failing with a crash. We fixed one issue.";
        assertEquals(DrawerDocument.TYPE_PROBLEM, MemoryClassifier.classify(text));
    }
}
