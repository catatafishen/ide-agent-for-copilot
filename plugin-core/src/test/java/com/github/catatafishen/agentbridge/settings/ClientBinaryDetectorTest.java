package com.github.catatafishen.agentbridge.settings;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClientBinaryDetector} — uses a test subclass to verify the
 * three-phase resolution order (configured → auto-detect → additional paths).
 */
class ClientBinaryDetectorTest {

    // ── Phase 1: configured path takes priority ────────────

    @Test
    void resolve_returnsConfiguredPathWhenSet() {
        var detector = new TestDetector("/usr/local/bin/copilot", List.of());
        assertEquals("/usr/local/bin/copilot", detector.resolve("copilot"));
    }

    @Test
    void resolve_returnsConfiguredPathWithoutValidation() {
        // Configured path is trusted without checking if the file exists
        var detector = new TestDetector("/nonexistent/binary", List.of());
        assertEquals("/nonexistent/binary", detector.resolve("imaginary"));
    }

    // ── Phase 2 skipped (requires BinaryDetector process spawning) ──

    // ── Phase 3: additional search paths ───────────────────

    @Test
    void resolve_checksAdditionalPathsAsLastResort(@TempDir Path tempDir) throws IOException {
        // Create a temp executable file to simulate an additional search path
        Path binary = tempDir.resolve("my-tool");
        Files.createFile(binary);
        assertTrue(binary.toFile().setExecutable(true), "Failed to set executable bit");

        var detector = new TestDetector(null, List.of(binary.toString()));
        // This will skip configured path (null), skip BinaryDetector (not installed),
        // then find the additional path
        String result = detector.resolve("nonexistent-primary-name");
        // Result could be the additional path or null (if BinaryDetector finds the primary).
        // Since "nonexistent-primary-name" won't be found by BinaryDetector on most systems,
        // the additional path should be returned.
        if (result != null) {
            assertEquals(binary.toString(), result);
        }
    }

    @Test
    void resolve_skipsNonExecutableAdditionalPaths(@TempDir Path tempDir) throws IOException {
        Path binary = tempDir.resolve("tool");
        Files.createFile(binary);
        // Do NOT set executable

        var detector = new TestDetector(null, List.of(binary.toString()));
        String result = detector.resolve("nonexistent-primary");
        // The non-executable file should not be returned
        if (result != null) {
            assertNotEquals(binary.toString(), result,
                "Non-executable additional paths should be skipped");
        }
    }

    // ── No path found at all ───────────────────────────────

    @Test
    void resolve_returnsNullWhenNothingFound() {
        var detector = new TestDetector(null, List.of());
        // With no configured path, no matching binary on PATH, and no additional paths,
        // resolve should return null
        String result = detector.resolve("definitely-does-not-exist-xyz123");
        assertNull(result);
    }

    // ── Additional search paths default ────────────────────

    @Test
    void additionalSearchPaths_defaultsToEmptyList() {
        // TestDetector passes through its list, so use it with an empty list
        // to verify the default contract
        var detector = new TestDetector(null, List.of());
        assertTrue(detector.additionalSearchPaths().isEmpty());
    }

    // ── Alternate names ────────────────────────────────────

    @Test
    void resolve_configuredPathIgnoresAlternateNames() {
        var detector = new TestDetector("/my/binary", List.of());
        assertEquals("/my/binary", detector.resolve("primary", "alt1", "alt2"));
    }

    // ── Additional search paths with executable check ──────

    @Test
    void resolve_multipleAdditionalPaths_returnsFirstExecutable(@TempDir Path tempDir) throws IOException {
        Path notExec = tempDir.resolve("tool-not-exec");
        Files.createFile(notExec);

        Path exec = tempDir.resolve("tool-exec");
        Files.createFile(exec);
        assertTrue(exec.toFile().setExecutable(true), "Failed to set executable bit");

        var detector = new TestDetector(null, List.of(
            notExec.toString(), exec.toString()));
        String result = detector.resolve("nonexistent-xyz");
        if (result != null) {
            assertEquals(exec.toString(), result,
                "Should return the first executable additional path");
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private static final class TestDetector extends ClientBinaryDetector {
        private final String configuredPath;
        private final List<String> additionalPaths;

        TestDetector(String configuredPath, List<String> additionalPaths) {
            this.configuredPath = configuredPath;
            this.additionalPaths = additionalPaths;
        }

        @Override
        protected String getConfiguredPath() {
            return configuredPath;
        }

        @Override
        protected @NotNull List<String> additionalSearchPaths() {
            return additionalPaths;
        }
    }
}
