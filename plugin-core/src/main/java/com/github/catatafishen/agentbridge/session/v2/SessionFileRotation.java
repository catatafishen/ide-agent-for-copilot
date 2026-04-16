package com.github.catatafishen.agentbridge.session.v2;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages rotation of session JSONL files to prevent unbounded growth.
 *
 * <p>File naming convention:
 * <ul>
 *   <li>{@code <sessionId>.jsonl} — the active file being appended to</li>
 *   <li>{@code <sessionId>.part-001.jsonl}, {@code .part-002.jsonl}, … — rotated parts</li>
 * </ul>
 *
 * <p>When loading, parts are read in numeric order, then the current {@code .jsonl} tail.
 *
 * <p>Rotation is triggered when:
 * <ol>
 *   <li>The active file exceeds {@link #MAX_FILE_SIZE_BYTES} (10 MB)</li>
 *   <li>The active file's last-modified date differs from today (date boundary)</li>
 *   <li>An explicit resume/export rotation is requested</li>
 * </ol>
 */
public final class SessionFileRotation {

    private static final Logger LOG = Logger.getInstance(SessionFileRotation.class);

    static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final String PART_FORMAT = ".part-%03d.jsonl";

    /**
     * Matches {@code <sessionId>.part-NNN.jsonl} where NNN is a zero-padded integer.
     * Capture group 1 = the part number.
     */
    private static final Pattern PART_PATTERN = Pattern.compile(
        "\\.part-(\\d{3})\\.jsonl$");

    private SessionFileRotation() {
    }

    /**
     * Returns all part files for a session, sorted by part number (ascending).
     * Does not include the current active {@code <sessionId>.jsonl} file.
     */
    @NotNull
    public static List<File> listPartFiles(@NotNull File dir, @NotNull String sessionId) {
        String prefix = sessionId + ".part-";
        File[] candidates = dir.listFiles(
            (d, name) -> name.startsWith(prefix) && name.endsWith(".jsonl"));
        if (candidates == null || candidates.length == 0) return List.of();

        List<File> parts = new ArrayList<>(Arrays.asList(candidates));
        parts.sort(Comparator.comparingInt(f -> extractPartNumber(f.getName())));
        return parts;
    }

    /**
     * Returns all files for a session in reading order: part files first (sorted),
     * then the current active {@code <sessionId>.jsonl} file (if it exists and is non-empty).
     */
    @NotNull
    public static List<Path> listAllFiles(@NotNull File dir, @NotNull String sessionId) {
        List<Path> result = new ArrayList<>();
        for (File part : listPartFiles(dir, sessionId)) {
            result.add(part.toPath());
        }

        File current = new File(dir, sessionId + ".jsonl");
        if (current.exists() && current.length() > 0) {
            result.add(current.toPath());
        }
        return result;
    }

    /**
     * Checks whether the active session file should be rotated before the next append.
     *
     * @param currentFile the active {@code <sessionId>.jsonl} file
     * @param clock       clock used for date comparison (injectable for tests)
     * @return {@code true} if the file exceeds the size limit or was last modified on a different day
     */
    static boolean shouldRotate(@NotNull File currentFile, @NotNull Clock clock) {
        if (!currentFile.exists() || currentFile.length() == 0) return false;

        if (currentFile.length() > MAX_FILE_SIZE_BYTES) return true;

        long lastModifiedMillis = currentFile.lastModified();
        if (lastModifiedMillis == 0) return false;

        LocalDate fileDate = Instant.ofEpochMilli(lastModifiedMillis)
            .atZone(clock.getZone())
            .toLocalDate();
        LocalDate today = LocalDate.now(clock);
        return !fileDate.equals(today);
    }

    /**
     * Rotates the current active file to the next part number.
     * After rotation, the caller should create a fresh {@code <sessionId>.jsonl} by appending.
     *
     * @param currentFile the active file to rotate
     * @param dir         the sessions directory
     * @param sessionId   the session UUID
     * @throws IOException if the rename fails
     */
    static void rotate(@NotNull File currentFile, @NotNull File dir,
                       @NotNull String sessionId) throws IOException {
        int nextPart = nextPartNumber(dir, sessionId);
        String partName = sessionId + String.format(PART_FORMAT, nextPart);
        File partFile = new File(dir, partName);

        Files.move(currentFile.toPath(), partFile.toPath());
        LOG.info("Rotated session " + sessionId + " → " + partName
            + " (" + (currentFile.length() / 1024) + " KB)");
    }

    /**
     * Rotates the active file if it exceeds the size limit or crosses a date boundary.
     * No-op if the file doesn't exist, is empty, or is within limits.
     *
     * @param currentFile the active {@code <sessionId>.jsonl} file
     * @param dir         the sessions directory
     * @param sessionId   the session UUID
     * @param clock       clock used for date comparison
     */
    public static void rotateIfNeeded(@NotNull File currentFile, @NotNull File dir,
                               @NotNull String sessionId, @NotNull Clock clock) {
        if (!shouldRotate(currentFile, clock)) return;
        try {
            rotate(currentFile, dir, sessionId);
        } catch (IOException e) {
            LOG.warn("Failed to rotate session file " + currentFile.getName(), e);
        }
    }

    /**
     * Forces rotation regardless of size/date checks. Used at resume points
     * and session switch boundaries to create a clean split.
     * No-op if the file doesn't exist or is empty.
     */
    public static void rotateForResume(@NotNull File currentFile, @NotNull File dir,
                                @NotNull String sessionId) {
        if (!currentFile.exists() || currentFile.length() == 0) return;
        try {
            rotate(currentFile, dir, sessionId);
        } catch (IOException e) {
            LOG.warn("Failed to rotate session file at resume: " + currentFile.getName(), e);
        }
    }

    /**
     * Returns the next available part number for a session.
     * Scans existing part files and returns max + 1 (starting at 1).
     */
    @VisibleForTesting
    static int nextPartNumber(@NotNull File dir, @NotNull String sessionId) {
        int max = 0;
        for (File part : listPartFiles(dir, sessionId)) {
            int num = extractPartNumber(part.getName());
            if (num > max) max = num;
        }
        return max + 1;
    }

    /**
     * Extracts the numeric part number from a part filename.
     * Returns 0 if the filename doesn't match the expected pattern.
     */
    private static int extractPartNumber(@NotNull String filename) {
        Matcher m = PART_PATTERN.matcher(filename);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
