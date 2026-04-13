package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link AgentProfileManager#formatAuthStatus(boolean, String)}.
 */
class AgentProfileManagerFormatAuthStatusTest {

    @Test
    void notLoggedIn_returnsNull() {
        assertNull(AgentProfileManager.formatAuthStatus(false, null));
    }

    @Test
    void notLoggedIn_withDisplayName_stillReturnsNull() {
        assertNull(AgentProfileManager.formatAuthStatus(false, "Alice"));
    }

    @Test
    void loggedIn_noDisplayName_returnsGenericMessage() {
        String result = AgentProfileManager.formatAuthStatus(true, null);
        assertNotNull(result);
        assertEquals("✓ Logged in", result);
    }

    @Test
    void loggedIn_withDisplayName_includesName() {
        String result = AgentProfileManager.formatAuthStatus(true, "Alice");
        assertNotNull(result);
        assertEquals("✓ Logged in as Alice", result);
    }

    @Test
    void loggedIn_withEmail_includesEmail() {
        String result = AgentProfileManager.formatAuthStatus(true, "alice@example.com");
        assertEquals("✓ Logged in as alice@example.com", result);
    }

    @Test
    void loggedIn_withEmptyDisplayName_treatsAsPresent() {
        // Empty string is not null, so " as " is appended
        String result = AgentProfileManager.formatAuthStatus(true, "");
        assertEquals("✓ Logged in as ", result);
    }
}
