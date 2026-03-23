package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Detects binaries using the user's full shell environment.
 * Works with any installation method (nvm, homebrew, system packages, etc.).
 */
public class BinaryDetector {
    private static final Logger LOG = Logger.getInstance(BinaryDetector.class);

    /**
     * Detect if a binary exists and get its version, trying alternate names if primary fails.
     * Uses the captured shell environment, so works with nvm, sdkman, etc.
     *
     * @param binaryName     Name of the binary (e.g., "junie", "copilot", "opencode")
     * @param alternateNames Alternate names to try if primary name not found
     * @return Version string like "v1.2.3", or null if not found
     */
    @Nullable
    public static String detectBinaryVersion(@NotNull String binaryName, @NotNull String[] alternateNames) {
        // Try primary name first
        String version = tryDetectBinary(binaryName);
        if (version != null) {
            return version;
        }

        // Try alternates
        for (String altName : alternateNames) {
            version = tryDetectBinary(altName);
            if (version != null) {
                return version;
            }
        }

        return null;
    }

    @Nullable
    private static String tryDetectBinary(@NotNull String binaryName) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows: use cmd.exe
                pb = new ProcessBuilder("cmd.exe", "/c", binaryName + " --version");
            } else {
                // Unix: use sh -c to respect PATH
                pb = new ProcessBuilder("sh", "-c", binaryName + " --version");
            }

            // Use captured shell environment which includes nvm, sdkman, etc.
            Map<String, String> shellEnv = ShellEnvironment.getEnvironment();
            pb.environment().clear();
            pb.environment().putAll(shellEnv);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String version = parseVersion(output.toString());
            if (version != null) {
                LOG.info("Detected " + binaryName + " version: " + version);
            }
            return version;

        } catch (Exception e) {
            LOG.debug("Failed to detect " + binaryName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the absolute path to a binary using the captured shell environment.
     *
     * @param binaryName Name of the binary to find
     * @return Absolute path, or null if not found
     */
    @Nullable
    public static String findBinaryPath(@NotNull String binaryName) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows: use where
                pb = new ProcessBuilder("cmd.exe", "/c", "where " + binaryName);
            } else {
                // Unix: use which
                pb = new ProcessBuilder("sh", "-c", "which " + binaryName);
            }

            // Use captured shell environment
            Map<String, String> shellEnv = ShellEnvironment.getEnvironment();
            pb.environment().clear();
            pb.environment().putAll(shellEnv);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String path = output.toString().trim();
            if (!path.isEmpty()) {
                // On Windows, 'where' may return multiple paths - take the first
                String[] lines = path.split("\n");
                path = lines[0].trim();
                LOG.info("Found " + binaryName + " at: " + path);
                return path;
            }
            return null;

        } catch (Exception e) {
            LOG.debug("Failed to find " + binaryName + " path: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String parseVersion(@NotNull String output) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Skip noise like "Welcome to", "Loading", etc.
            if (line.toLowerCase().contains("welcome") ||
                line.toLowerCase().contains("loading") ||
                line.toLowerCase().contains("initializing")) {
                continue;
            }

            // Look for version patterns: "1.2.3" or "v1.2.3"
            if (line.matches(".*\\d+\\.\\d+.*")) {
                return line;
            }
        }
        return null;
    }
}
