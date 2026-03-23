package com.github.catatafishen.ideagentforcopilot.acp.model;

/**
 * Agent → Client: session update notification.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/prompt-turn#3-agent-reports-output">ACP Session Updates</a>
 */
public record SessionNotification(
        String sessionId,
        SessionUpdate update
) {}
