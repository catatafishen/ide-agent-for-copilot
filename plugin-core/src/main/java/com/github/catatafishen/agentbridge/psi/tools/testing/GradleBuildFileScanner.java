package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans Gradle build files for custom test task registrations.
 * Pure filesystem logic — no IntelliJ Platform dependencies.
 */
public final class GradleBuildFileScanner {

    /**
     * Regex that matches Kotlin DSL and Groovy DSL test task registrations.
     * Each alternative captures the task name in its respective group.
     */
    static final Pattern GRADLE_TEST_TASK_PATTERN =
        Pattern.compile(
            "tasks\\.register\\(\"([a-zA-Z][a-zA-Z0-9]*)\",\\s*Test::" +
                "|tasks\\.register<Test>\\(\"([a-zA-Z][a-zA-Z0-9]*)\"" +
                "|val\\s+([a-zA-Z][a-zA-Z0-9]*)\\s+by\\s+tasks\\.registering\\(Test" +
                "|\\btask\\s+([a-zA-Z][a-zA-Z0-9]*)\\s*\\(\\s*type\\s*:\\s*Test" +
                "|tasks\\.register\\('([a-zA-Z][a-zA-Z0-9]*)',\\s*Test"
        );

    private static final List<String> BUILD_FILE_NAMES =
        List.of("build.gradle.kts", "build.gradle");

    private GradleBuildFileScanner() {
    }

    @Nullable
    public static String detectTestTask(@NotNull String basePath) {
        File root = new File(basePath);
        String task = scanDirectory(root);
        if (task != null) return task;

        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs == null) return null;
        for (File dir : subdirs) {
            task = scanDirectory(dir);
            if (task != null) return task;
        }
        return null;
    }

    @Nullable
    private static String scanDirectory(File dir) {
        for (String fileName : BUILD_FILE_NAMES) {
            String content = readBuildFileQuietly(new File(dir, fileName));
            if (content != null) {
                String task = findTestTaskInBuildFile(content);
                if (task != null) return task;
            }
        }
        return null;
    }

    /**
     * Reads a build file's content, returning null if the file doesn't exist or can't be read.
     */
    @Nullable
    static String readBuildFileQuietly(File file) {
        if (!file.exists()) return null;
        try {
            return Files.readString(file.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scans a single Gradle build file's content for non-standard test task registrations
     * (tasks of type {@code Test} with a name other than "test").
     * Supports Kotlin DSL and Groovy DSL patterns.
     *
     * @return the first custom test task name found, or {@code null}
     */
    @Nullable
    public static String findTestTaskInBuildFile(@NotNull String content) {
        var matcher = GRADLE_TEST_TASK_PATTERN.matcher(content);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String name = matcher.group(i);
                if (name != null && !"test".equals(name)) return name;
            }
        }
        return null;
    }
}
