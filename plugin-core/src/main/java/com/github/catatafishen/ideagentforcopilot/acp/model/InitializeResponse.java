package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Agent → Client: initialization handshake response with capabilities and auth methods.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/initialization">ACP Initialization</a>
 */
public record InitializeResponse(
        AgentInfo agentInfo,
        AgentCapabilities agentCapabilities,
        @Nullable List<AuthMethod> authMethods
) {

    public record AgentInfo(String name, String version) {}

    public record AgentCapabilities(
            @Nullable Boolean loadSession,
            @Nullable McpCapabilities mcpCapabilities,
            @Nullable PromptCapabilities promptCapabilities,
            @Nullable SessionCapabilities sessionCapabilities
    ) {}

    public record McpCapabilities(
            @Nullable Boolean http,
            @Nullable Boolean sse
    ) {}

    public record PromptCapabilities(
            @Nullable Boolean image,
            @Nullable Boolean audio,
            @Nullable Boolean embeddedContext
    ) {}

    public record SessionCapabilities() {}
}
