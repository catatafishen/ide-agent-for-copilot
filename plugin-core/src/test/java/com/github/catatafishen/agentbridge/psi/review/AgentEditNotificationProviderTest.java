package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditNotificationProviderTest {

    @Test
    void bannerTextShowsEditedByAgentWhenPending() {
        String msg = AgentEditNotificationProvider.formatBannerText("Edited by agent", 1, 3, 2);

        assertTrue(msg.startsWith("Edited by agent:"),
            "Banner should lead with the agent-edit state: " + msg);
        assertTrue(msg.contains("File 1/3"),
            "Banner should still include the file counter: " + msg);
        assertTrue(msg.contains("2 changes"),
            "Banner should still include the change counter: " + msg);
    }

    @Test
    void bannerTextShowsAcceptedWhenApproved() {
        String msg = AgentEditNotificationProvider.formatBannerText("✓ Accepted", 1, 3, 2);

        assertTrue(msg.startsWith("✓ Accepted:"),
            "Banner should show accepted state: " + msg);
        assertTrue(msg.contains("File 1/3"),
            "Banner should still include the file counter: " + msg);
    }

    @Test
    void bannerTextFallsBackWhenCountersAreEmpty() {
        String msg = AgentEditNotificationProvider.formatBannerText("Edited by agent", 0, 0, 0);

        assertTrue(msg.contains("No outstanding changes"),
            "Banner should remain readable when counters are unavailable: " + msg);
    }

    @Test
    void bannerTextIsDeterministic() {
        assertEquals(
            AgentEditNotificationProvider.formatBannerText("Edited by agent", 2, 5, 4),
            AgentEditNotificationProvider.formatBannerText("Edited by agent", 2, 5, 4)
        );
    }
}
