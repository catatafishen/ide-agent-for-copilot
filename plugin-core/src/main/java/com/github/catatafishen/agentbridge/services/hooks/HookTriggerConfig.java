package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Configuration for a single trigger within a hook definition.
 * Maps to one entry inside the {@code "hooks"} object of a {@code hook.json} file.
 *
 * @param bash         path to bash script (relative to hooks directory), required on Unix
 * @param powershell   path to PowerShell script, required on Windows
 * @param timeoutSec   max execution time before force-kill (default 10)
 * @param failOpen     if true, hook failure is silent; if false, hook failure fails the tool call
 * @param env          extra environment variables merged into the process
 * @param cwd          working directory override (relative to project root; defaults to hooks directory)
 */
public record HookTriggerConfig(
    @Nullable String bash,
    @Nullable String powershell,
    int timeoutSec,
    boolean failOpen,
    @NotNull Map<String, String> env,
    @Nullable String cwd
) {

    private static final int DEFAULT_TIMEOUT_SEC = 10;

    /**
     * Creates a config with defaults for omitted fields.
     */
    public HookTriggerConfig {
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;
        if (env == null) env = Map.of();
    }

    /**
     * Returns the script path appropriate for the current OS.
     */
    public @Nullable String scriptForCurrentOs() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return powershell;
        }
        return bash;
    }
}
