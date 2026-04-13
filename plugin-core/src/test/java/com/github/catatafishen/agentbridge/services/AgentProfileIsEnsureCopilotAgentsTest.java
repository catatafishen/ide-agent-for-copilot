package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentProfile#isEnsureCopilotAgents()}.
 */
class AgentProfileIsEnsureCopilotAgentsTest {

    @Test
    void defaultProfile_returnsFalse() {
        AgentProfile profile = new AgentProfile();
        assertFalse(profile.isEnsureCopilotAgents());
    }

    @Test
    void emptyBundledFiles_returnsFalse() {
        AgentProfile profile = new AgentProfile();
        profile.setBundledAgentFiles(List.of());
        assertFalse(profile.isEnsureCopilotAgents());
    }

    @Test
    void nonEmptyBundledFiles_returnsTrue() {
        AgentProfile profile = new AgentProfile();
        profile.setBundledAgentFiles(List.of("agent-explore.md"));
        assertTrue(profile.isEnsureCopilotAgents());
    }

    @Test
    void multipleBundledFiles_returnsTrue() {
        AgentProfile profile = new AgentProfile();
        profile.setBundledAgentFiles(List.of("agent-explore.md", "agent-task.md"));
        assertTrue(profile.isEnsureCopilotAgents());
    }
}
