package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Agent → Client: response to session creation with session ID, models, and capabilities.
 * <p>
 * {@code models} and {@code modes} are object maps (keyed by ID), not arrays.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record NewSessionResponse(
    String sessionId,
    @Nullable Map<String, Model> models,
    @Nullable Map<String, AvailableMode> modes,
    @Nullable List<AvailableCommand> commands,
    @Nullable List<SessionConfigOption> configOptions
) {

    public record AvailableMode(
        String slug,
        String name,
        @Nullable String description
    ) {
    }

    public record AvailableCommand(
        String name,
        String description,
        @Nullable AvailableCommandInput input
    ) {
    }

    public record AvailableCommandInput(
        String type,
        @Nullable String placeholder
    ) {
    }

    public record SessionConfigOption(
        String id,
        String label,
        @Nullable String description,
        List<SessionConfigOptionValue> values,
        @Nullable String selectedValueId
    ) {
    }

    public record SessionConfigOptionValue(String id, String label) {
    }
}
