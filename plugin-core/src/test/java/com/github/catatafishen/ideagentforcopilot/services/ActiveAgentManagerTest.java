package com.github.catatafishen.ideagentforcopilot.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveAgentManagerTest {

    @Test
    void normalizeSharedTurnTimeoutMinutesUsesStoredMinutesAndClamps() {
        assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("2000", 300));
        assertEquals(5, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("bad", 3600));
    }

    @Test
    void normalizeSharedTurnTimeoutMinutesFallsBackToLegacySecondsRoundedUp() {
        assertEquals(60, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(null, 3600));
        assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(null, 1));
    }

    @Test
    void normalizeSharedInactivityTimeoutSecondsUsesStoredValueAndClamps() {
        assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("10", 900));
        assertEquals(300, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("bad", 900));
        assertEquals(900, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(null, 900));
    }

    @Test
    void normalizeSharedMaxToolCallsPerTurnUsesStoredValueAndClamps() {
        assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("-5", 17));
        assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("bad", 17));
        assertEquals(17, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, 17));
    }
}
