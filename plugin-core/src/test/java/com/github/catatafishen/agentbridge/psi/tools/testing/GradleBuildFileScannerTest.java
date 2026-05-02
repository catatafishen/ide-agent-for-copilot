package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GradleBuildFileScanner} — pure filesystem and regex logic,
 * no IntelliJ Platform dependencies.
 */
@DisplayName("GradleBuildFileScanner")
class GradleBuildFileScannerTest {

    @Nested
    @DisplayName("findTestTaskInBuildFile")
    class FindTestTaskInBuildFile {

        @Test
        @DisplayName("returns null for empty content")
        void emptyContent() {
            assertNull(GradleBuildFileScanner.findTestTaskInBuildFile(""));
        }

        @Test
        @DisplayName("returns null for standard 'test' task only")
        void standardTestTaskOnly() {
            assertNull(GradleBuildFileScanner.findTestTaskInBuildFile(
                "tasks.register(\"test\", Test::class.java)"));
        }

        @Test
        @DisplayName("detects Kotlin DSL register with Test::class")
        void kotlinDslRegisterTestClass() {
            assertEquals("unitTest", GradleBuildFileScanner.findTestTaskInBuildFile(
                "tasks.register(\"unitTest\", Test::class.java) {\n  useJUnitPlatform()\n}"));
        }

        @Test
        @DisplayName("detects Kotlin DSL register<Test>")
        void kotlinDslRegisterGeneric() {
            assertEquals("integrationTest", GradleBuildFileScanner.findTestTaskInBuildFile(
                "tasks.register<Test>(\"integrationTest\") {\n  useJUnitPlatform()\n}"));
        }

        @Test
        @DisplayName("detects Kotlin DSL val by tasks.registering")
        void kotlinDslDelegateProperty() {
            assertEquals("functionalTest", GradleBuildFileScanner.findTestTaskInBuildFile(
                "val functionalTest by tasks.registering(Test::class) {\n  useJUnitPlatform()\n}"));
        }

        @Test
        @DisplayName("detects Groovy DSL task with type: Test")
        void groovyDslTaskType() {
            assertEquals("smokeTest", GradleBuildFileScanner.findTestTaskInBuildFile(
                "task smokeTest(type: Test) {\n  useJUnitPlatform()\n}"));
        }

        @Test
        @DisplayName("detects Groovy DSL register with single-quoted name")
        void groovyDslRegister() {
            assertEquals("e2eTest", GradleBuildFileScanner.findTestTaskInBuildFile(
                "tasks.register('e2eTest', Test) {\n  useJUnitPlatform()\n}"));
        }

        @Test
        @DisplayName("returns first non-test task when multiple present")
        void multipleTasksReturnsFirst() {
            String content = """
                tasks.register("test", Test::class.java) { useJUnitPlatform() }
                tasks.register("unitTest", Test::class.java) { useJUnitPlatform() }
                tasks.register("integrationTest", Test::class.java) { useJUnitPlatform() }
                """;
            assertEquals("unitTest", GradleBuildFileScanner.findTestTaskInBuildFile(content));
        }

        @Test
        @DisplayName("returns null for unrelated content")
        void unrelatedContent() {
            assertNull(GradleBuildFileScanner.findTestTaskInBuildFile(
                "dependencies {\n  testImplementation(\"junit:junit:4.13\")\n}"));
        }
    }

    @Nested
    @DisplayName("readBuildFileQuietly")
    class ReadBuildFileQuietly {

        @Test
        @DisplayName("returns null for nonexistent file")
        void nonexistentFile() {
            assertNull(GradleBuildFileScanner.readBuildFileQuietly(
                new File("/nonexistent/path/build.gradle.kts")));
        }

        @Test
        @DisplayName("reads existing file content")
        void readsExistingFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("build.gradle.kts");
            Files.writeString(file, "plugins { id(\"java\") }");
            assertEquals("plugins { id(\"java\") }",
                GradleBuildFileScanner.readBuildFileQuietly(file.toFile()));
        }
    }

    @Nested
    @DisplayName("detectTestTask")
    class DetectTestTask {

        @Test
        @DisplayName("returns null for empty directory")
        void emptyDirectory(@TempDir Path tempDir) {
            assertNull(GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }

        @Test
        @DisplayName("returns null for nonexistent path")
        void nonexistentPath() {
            assertNull(GradleBuildFileScanner.detectTestTask("/nonexistent/project/path"));
        }

        @Test
        @DisplayName("detects task in root build.gradle.kts")
        void rootKotlinDsl(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("build.gradle.kts"),
                "tasks.register(\"unitTest\", Test::class.java) { useJUnitPlatform() }");
            assertEquals("unitTest", GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }

        @Test
        @DisplayName("detects task in root build.gradle")
        void rootGroovyDsl(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("build.gradle"),
                "task integrationTest(type: Test) { useJUnitPlatform() }");
            assertEquals("integrationTest", GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }

        @Test
        @DisplayName("prefers build.gradle.kts over build.gradle in same directory")
        void prefersKotlinDsl(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("build.gradle.kts"),
                "tasks.register(\"fromKotlin\", Test::class.java) {}");
            Files.writeString(tempDir.resolve("build.gradle"),
                "task fromGroovy(type: Test) {}");
            assertEquals("fromKotlin", GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }

        @Test
        @DisplayName("scans subdirectories when root has no custom task")
        void subdirectoryFallback(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("build.gradle.kts"),
                "plugins { id(\"java\") }");
            Path submod = tempDir.resolve("submodule");
            Files.createDirectory(submod);
            Files.writeString(submod.resolve("build.gradle.kts"),
                "tasks.register(\"unitTest\", Test::class.java) { useJUnitPlatform() }");
            assertEquals("unitTest", GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }

        @Test
        @DisplayName("returns null when no build files have custom tasks")
        void noCustomTasks(@TempDir Path tempDir) throws IOException {
            Files.writeString(tempDir.resolve("build.gradle.kts"),
                "dependencies { testImplementation(\"junit:junit:4.13\") }");
            assertNull(GradleBuildFileScanner.detectTestTask(tempDir.toString()));
        }
    }
}
