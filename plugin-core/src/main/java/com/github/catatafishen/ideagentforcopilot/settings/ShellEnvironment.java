package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Captures and caches the user's full shell environment (including nvm, sdkman, etc.).
 * This environment is used for both binary detection and runtime execution.
 */
public class ShellEnvironment {
    private static final Logger LOG = Logger.getInstance(ShellEnvironment.class);
    private static volatile Map<String, String> cachedEnvironment = null;
    private static final Object LOCK = new Object();

    /**
     * Get the captured shell environment, capturing it on first call and caching thereafter.
     *
     * @return Map of environment variables, or empty map if capture fails
     */
    @NotNull
    public static Map<String, String> getEnvironment() {
        if (cachedEnvironment != null) {
            return cachedEnvironment;
        }

        synchronized (LOCK) {
            if (cachedEnvironment != null) {
                return cachedEnvironment;
            }
            cachedEnvironment = captureEnvironment();
            return cachedEnvironment;
        }
    }

    /**
     * Force a re-capture of the shell environment (e.g., after user installs a new tool).
     */
    public static void refresh() {
        synchronized (LOCK) {
            cachedEnvironment = null;
        }
    }

    @NotNull
    private static Map<String, String> captureEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            return captureWindowsEnvironment();
        } else {
            return captureUnixEnvironment();
        }
    }

    @NotNull
    private static Map<String, String> captureUnixEnvironment() {
        try {
            // Use interactive shell to get full environment including nvm, sdkman from .bashrc
            // Redirect stderr to suppress "cannot set terminal process group" warnings
            ProcessBuilder pb = new ProcessBuilder("bash", "-i", "-c", "env 2>/dev/null");
            pb.redirectErrorStream(false);

            Process process = pb.start();
            Map<String, String> env = new HashMap<>();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf('=');
                    if (idx > 0) {
                        String key = line.substring(0, idx);
                        String value = line.substring(idx + 1);
                        env.put(key, value);
                    }
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Shell environment capture timed out");
            }

            if (env.isEmpty()) {
                LOG.warn("Failed to capture shell environment, using system environment");
                return System.getenv();
            }

            LOG.info("Captured shell environment with PATH: " + env.get("PATH"));
            return Collections.unmodifiableMap(env);

        } catch (Exception e) {
            LOG.warn("Failed to capture shell environment: " + e.getMessage(), e);
            return System.getenv();
        }
    }

    @NotNull
    private static Map<String, String> captureWindowsEnvironment() {
        try {
            // Use PowerShell with simpler output format for better Unicode support
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "[Environment]::GetEnvironmentVariables('Process') | ForEach-Object { $_.GetEnumerator() } | ForEach-Object { \"$($_.Key)=$($_.Value)\" }");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            Map<String, String> env = new HashMap<>();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    int idx = line.indexOf('=');
                    if (idx > 0) {
                        String key = line.substring(0, idx);
                        String value = line.substring(idx + 1);
                        env.put(key, value);
                    }
                }
                LOG.info("Read " + lineCount + " lines from PowerShell, captured " + env.size() + " variables");
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Windows environment capture timed out");
            }

            if (env.isEmpty()) {
                LOG.warn("Failed to capture Windows environment, using system environment");
                return System.getenv();
            }

            LOG.info("Captured Windows environment with PATH: " + env.get("PATH") + ", Path: " + env.get("Path"));
            return Collections.unmodifiableMap(env);

        } catch (Exception e) {
            LOG.warn("Failed to capture Windows environment: " + e.getMessage(), e);
            return System.getenv();
        }
    }

    /**
     * Get PATH from the captured environment, or system PATH if capture failed.
     */
    @NotNull
    public static String getPath() {
        Map<String, String> env = getEnvironment();
        String path = env.get("PATH");
        if (path == null || path.isEmpty()) {
            path = System.getenv("PATH");
        }
        return path != null ? path : "";
    }
}
