package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Client → Agent: authenticate with a specific method.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/initialization">ACP Authentication</a>
 */
public record AuthenticateRequest(String methodId) {}
