package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for private static methods in {@link RunConfigurationService} via reflection.
 */
class RunConfigurationServiceStaticMethodsTest {

    private static Method parseTaskNames;
    private static Method checkProgramArgsAbuse;

    @BeforeAll
    static void setup() throws Exception {
        parseTaskNames = RunConfigurationService.class.getDeclaredMethod(
            "parseTaskNames", com.google.gson.JsonElement.class);
        parseTaskNames.setAccessible(true);

        checkProgramArgsAbuse = RunConfigurationService.class.getDeclaredMethod(
            "checkProgramArgsAbuse", JsonObject.class, String.class);
        checkProgramArgsAbuse.setAccessible(true);
    }

    @Nested
    class ParseTaskNames {

        @SuppressWarnings("unchecked")
        private List<String> call(com.google.gson.JsonElement elem) throws Exception {
            return (List<String>) parseTaskNames.invoke(null, elem);
        }

        @Test
        void parsesJsonArray() throws Exception {
            JsonArray arr = new JsonArray();
            arr.add(":core:build");
            arr.add(":app:test");
            List<String> result = call(arr);
            assertEquals(List.of(":core:build", ":app:test"), result);
        }

        @Test
        void parsesSpaceSeparatedString() throws Exception {
            List<String> result = call(new JsonPrimitive("clean build test"));
            assertEquals(List.of("clean", "build", "test"), result);
        }

        @Test
        void singleTaskString() throws Exception {
            List<String> result = call(new JsonPrimitive("build"));
            assertEquals(List.of("build"), result);
        }

        @Test
        void emptyArrayReturnsEmptyList() throws Exception {
            List<String> result = call(new JsonArray());
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyStringReturnsEmptyList() throws Exception {
            List<String> result = call(new JsonPrimitive(""));
            assertTrue(result.isEmpty());
        }

        @Test
        void multipleSpacesHandledCorrectly() throws Exception {
            List<String> result = call(new JsonPrimitive("clean   build"));
            assertEquals(List.of("clean", "build"), result);
        }
    }

    @Nested
    class CheckProgramArgsAbuse {

        private String call(JsonObject args, String configType) throws Exception {
            return (String) checkProgramArgsAbuse.invoke(null, args, configType);
        }

        @Test
        void noProgramArgsReturnsNull() throws Exception {
            JsonObject args = new JsonObject();
            assertNull(call(args, "application"));
        }

        @Test
        void normalArgsAllowed() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "--verbose --port 8080");
            assertNull(call(args, "application"));
        }

        @Test
        void gitCommandBlocked() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "git push origin main");
            String result = call(args, "application");
            assertNotNull(result);
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        void gradleTestTaskBlocked() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "test --info");
            String result = call(args, "gradle");
            assertNotNull(result);
            assertTrue(result.contains("run_tests"));
        }

        @Test
        void gradleTestTaskAllowedForNonGradleType() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "test --info");
            // "test" alone doesn't match general abuse patterns, only gradle-specific check
            assertNull(call(args, "application"));
        }

        @Test
        void sedCommandBlocked() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "sed -i 's/foo/bar/g' file.txt");
            String result = call(args, "application");
            assertNotNull(result);
            assertTrue(result.contains("edit_text"));
        }

        @Test
        void nullConfigTypeAllowed() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", "test --info");
            // null configType means we only run general abuse detection (test alone won't match)
            assertNull(call(args, null));
        }
    }
}
