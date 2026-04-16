package com.github.catatafishen.agentbridge.session.v2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionFileRotationTest {

    private static final String SESSION_ID = "test-session-abc";

    @TempDir
    Path tempDir;

    private File sessionsDir;

    @BeforeEach
    void setUp() {
        sessionsDir = tempDir.toFile();
    }

    @Test
    void listPartFiles_returnsEmptyWhenNoParts() {
        List<File> parts = SessionFileRotation.listPartFiles(sessionsDir, SESSION_ID);
        assertTrue(parts.isEmpty());
    }

    @Test
    void listPartFiles_returnsSortedParts() throws IOException {
        // Create parts out of order to verify sorting
        createFile(SESSION_ID + ".part-003.jsonl", "line3");
        createFile(SESSION_ID + ".part-001.jsonl", "line1");
        createFile(SESSION_ID + ".part-002.jsonl", "line2");
        // Create unrelated files that should not be included
        createFile("other-session.part-001.jsonl", "other");
        createFile(SESSION_ID + ".jsonl", "active");

        List<File> parts = SessionFileRotation.listPartFiles(sessionsDir, SESSION_ID);
        assertEquals(3, parts.size());
        assertTrue(parts.get(0).getName().contains("part-001"));
        assertTrue(parts.get(1).getName().contains("part-002"));
        assertTrue(parts.get(2).getName().contains("part-003"));
    }

    @Test
    void listAllFiles_includesPartsAndActiveFile() throws IOException {
        createFile(SESSION_ID + ".part-001.jsonl", "part1");
        createFile(SESSION_ID + ".part-002.jsonl", "part2");
        createFile(SESSION_ID + ".jsonl", "active");

        List<Path> all = SessionFileRotation.listAllFiles(sessionsDir, SESSION_ID);
        assertEquals(3, all.size());
        // Parts come first, active file last
        assertTrue(all.get(0).getFileName().toString().contains("part-001"));
        assertTrue(all.get(1).getFileName().toString().contains("part-002"));
        assertEquals(SESSION_ID + ".jsonl", all.get(2).getFileName().toString());
    }

    @Test
    void listAllFiles_excludesEmptyActiveFile() throws IOException {
        createFile(SESSION_ID + ".part-001.jsonl", "part1");
        createFile(SESSION_ID + ".jsonl", "");

        List<Path> all = SessionFileRotation.listAllFiles(sessionsDir, SESSION_ID);
        assertEquals(1, all.size());
        assertTrue(all.get(0).getFileName().toString().contains("part-001"));
    }

    @Test
    void listAllFiles_returnsEmptyWhenNoFiles() {
        List<Path> all = SessionFileRotation.listAllFiles(sessionsDir, SESSION_ID);
        assertTrue(all.isEmpty());
    }

    @Test
    void shouldRotate_returnsFalseForNonExistentFile() {
        File file = new File(sessionsDir, SESSION_ID + ".jsonl");
        assertFalse(SessionFileRotation.shouldRotate(file, Clock.systemDefaultZone()));
    }

    @Test
    void shouldRotate_returnsFalseForEmptyFile() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "");
        assertFalse(SessionFileRotation.shouldRotate(file, Clock.systemDefaultZone()));
    }

    @Test
    void shouldRotate_returnsFalseWhenUnderSizeLimit() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "small content");
        assertFalse(SessionFileRotation.shouldRotate(file, Clock.systemDefaultZone()));
    }

    @Test
    void shouldRotate_returnsTrueWhenOverSizeLimit() throws IOException {
        // Create a file just over 10MB
        byte[] data = new byte[(int) (SessionFileRotation.MAX_FILE_SIZE_BYTES + 1)];
        File file = new File(sessionsDir, SESSION_ID + ".jsonl");
        Files.write(file.toPath(), data);
        assertTrue(SessionFileRotation.shouldRotate(file, Clock.systemDefaultZone()));
    }

    @Test
    void shouldRotate_returnsTrueOnDateBoundary() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "some data");
        // Set file modification time to yesterday
        long yesterday = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        assertTrue(file.setLastModified(yesterday));

        assertTrue(SessionFileRotation.shouldRotate(file, Clock.systemDefaultZone()));
    }

    @Test
    void shouldRotate_returnsFalseWhenSameDayWithFixedClock() throws IOException {
        Instant fixedInstant = Instant.parse("2025-06-15T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        File file = createFile(SESSION_ID + ".jsonl", "some data");
        // Set modification time to same day
        long sameDayMs = fixedInstant.minus(Duration.ofHours(2)).toEpochMilli();
        assertTrue(file.setLastModified(sameDayMs));

        assertFalse(SessionFileRotation.shouldRotate(file, fixedClock));
    }

    @Test
    void rotate_movesFileToNextPart() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "content to rotate");
        SessionFileRotation.rotate(file, sessionsDir, SESSION_ID);

        assertFalse(file.exists(), "Active file should have been moved");
        File part1 = new File(sessionsDir, SESSION_ID + ".part-001.jsonl");
        assertTrue(part1.exists(), "Part file should exist");
        assertEquals("content to rotate", Files.readString(part1.toPath()));
    }

    @Test
    void rotate_incrementsPartNumber() throws IOException {
        createFile(SESSION_ID + ".part-001.jsonl", "part1");
        createFile(SESSION_ID + ".part-002.jsonl", "part2");

        File active = createFile(SESSION_ID + ".jsonl", "active content");
        SessionFileRotation.rotate(active, sessionsDir, SESSION_ID);

        assertFalse(active.exists());
        File part3 = new File(sessionsDir, SESSION_ID + ".part-003.jsonl");
        assertTrue(part3.exists());
        assertEquals("active content", Files.readString(part3.toPath()));
    }

    @Test
    void rotateIfNeeded_doesNothingWhenUnderThreshold() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "small");
        SessionFileRotation.rotateIfNeeded(file, sessionsDir, SESSION_ID, Clock.systemDefaultZone());
        assertTrue(file.exists(), "File should not have been rotated");
    }

    @Test
    void rotateForResume_forcesRotation() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "content");
        SessionFileRotation.rotateForResume(file, sessionsDir, SESSION_ID);

        assertFalse(file.exists(), "Active file should have been moved");
        File part1 = new File(sessionsDir, SESSION_ID + ".part-001.jsonl");
        assertTrue(part1.exists());
    }

    @Test
    void rotateForResume_noOpForEmptyFile() throws IOException {
        File file = createFile(SESSION_ID + ".jsonl", "");
        SessionFileRotation.rotateForResume(file, sessionsDir, SESSION_ID);
        assertTrue(file.exists(), "Empty file should not be rotated");
    }

    @Test
    void rotateForResume_noOpForNonExistentFile() {
        File file = new File(sessionsDir, SESSION_ID + ".jsonl");
        assertDoesNotThrow(() ->
            SessionFileRotation.rotateForResume(file, sessionsDir, SESSION_ID));
    }

    private File createFile(String name, String content) throws IOException {
        File file = new File(sessionsDir, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }
}
