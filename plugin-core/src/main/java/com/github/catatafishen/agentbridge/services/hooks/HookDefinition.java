package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code hook.json} file describing one hook that can apply to one or more tools.
 * Each hook can define triggers for any subset of {@link HookTrigger} types.
 *
 * @param version     schema version (currently 1)
 * @param id          unique identifier (kebab-case)
 * @param name        human-readable name shown in settings UI and run tabs
 * @param description what the hook does (optional)
 * @param tools       tool IDs this hook applies to
 * @param hooks       trigger → config mapping
 * @param sourceDir   directory containing the hook.json (used to resolve relative script paths)
 */
public record HookDefinition(
    int version,
    @NotNull String id,
    @NotNull String name,
    @Nullable String description,
    @NotNull List<String> tools,
    @NotNull Map<HookTrigger, HookTriggerConfig> hooks,
    @NotNull Path sourceDir
) {

    /**
     * Returns the trigger config for the given trigger type, or null if not defined.
     */
    public @Nullable HookTriggerConfig triggerConfig(@NotNull HookTrigger trigger) {
        return hooks.get(trigger);
    }

    /**
     * Returns true if this hook applies to the given tool ID.
     */
    public boolean appliesTo(@NotNull String toolId) {
        return tools.contains(toolId);
    }

    /**
     * Resolves the script path for a trigger against the hook's source directory.
     */
    public @Nullable Path resolveScript(@NotNull HookTrigger trigger) {
        HookTriggerConfig config = hooks.get(trigger);
        if (config == null) return null;
        String script = config.scriptForCurrentOs();
        if (script == null) return null;
        return sourceDir.resolve(script);
    }
}
