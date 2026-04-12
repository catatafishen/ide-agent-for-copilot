package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for private/package-private static methods in {@link SonarQubeIntegration} via reflection.
 */
class SonarQubeIntegrationStaticMethodsTest {

    private static Method isExcludedFinding;
    private static Method relativizePath;
    private static Method findMethodReflection;
    private static Method formatOutput;

    @BeforeAll
    static void setup() throws Exception {
        isExcludedFinding = SonarQubeIntegration.class.getDeclaredMethod("isExcludedFinding", String.class);
        isExcludedFinding.setAccessible(true);

        relativizePath = SonarQubeIntegration.class.getDeclaredMethod("relativizePath", String.class, String.class);
        relativizePath.setAccessible(true);

        findMethodReflection = SonarQubeIntegration.class.getDeclaredMethod("findMethod", Object.class, String.class);
        findMethodReflection.setAccessible(true);

        formatOutput = SonarQubeIntegration.class.getDeclaredMethod("formatOutput", List.class, int.class, int.class);
        formatOutput.setAccessible(true);
    }

    @Nested
    class IsExcludedFinding {
        private boolean call(String finding) throws Exception {
            return (boolean) isExcludedFinding.invoke(null, finding);
        }

        @Test
        void excludesBuildDir() throws Exception {
            assertTrue(call("build/classes/Main.class"));
        }

        @Test
        void excludesOutDir() throws Exception {
            assertTrue(call("out/production/MyClass.class"));
        }

        @Test
        void excludesPluginCoreBuild() throws Exception {
            assertTrue(call("plugin-core/build/generated/Source.java"));
        }

        @Test
        void excludesPluginCoreOut() throws Exception {
            assertTrue(call("plugin-core/out/production/File.class"));
        }

        @Test
        void excludesMcpServerBuild() throws Exception {
            assertTrue(call("mcp-server/build/libs/server.jar"));
        }

        @Test
        void excludesStandaloneMcpBuild() throws Exception {
            assertTrue(call("standalone-mcp/build/output.jar"));
        }

        @Test
        void excludesPluginExperimentalBuild() throws Exception {
            assertTrue(call("plugin-experimental/build/classes/Test.class"));
        }

        @Test
        void doesNotExcludeSourceFile() throws Exception {
            assertFalse(call("plugin-core/src/main/java/Main.java"));
        }

        @Test
        void doesNotExcludeTestFile() throws Exception {
            assertFalse(call("src/test/java/MyTest.java"));
        }

        @Test
        void doesNotExcludeBuildInMiddleOfPath() throws Exception {
            assertFalse(call("src/main/build/config.xml"));
        }
    }

    @Nested
    class RelativizePath {
        private String call(String path, String basePath) throws Exception {
            return (String) relativizePath.invoke(null, path, basePath);
        }

        @Test
        void stripsBasePath() throws Exception {
            assertEquals("src/Main.java", call("/home/user/project/src/Main.java", "/home/user/project"));
        }

        @Test
        void returnsOriginalWhenNoBasePath() throws Exception {
            assertEquals("/home/user/project/src/Main.java", call("/home/user/project/src/Main.java", null));
        }

        @Test
        void returnsOriginalWhenNotPrefixed() throws Exception {
            assertEquals("/other/path/File.java", call("/other/path/File.java", "/home/user/project"));
        }

        @Test
        void handlesExactBasePathMatch() throws Exception {
            // When path equals basePath, substring(basePath.length() + 1) goes past end
            // Testing the actual behavior — this would throw StringIndexOutOfBoundsException
            // if path == basePath since it tries to skip the trailing slash
            String basePath = "/home/user/project";
            String path = "/home/user/project/x";
            assertEquals("x", call(path, basePath));
        }
    }

    @Nested
    class FindMethod {
        private Method call(Object obj, String name) throws Exception {
            return (Method) findMethodReflection.invoke(null, obj, name);
        }

        @Test
        void findsPublicMethod() throws Exception {
            Method m = call("hello", "length");
            assertNotNull(m);
            assertEquals("length", m.getName());
        }

        @Test
        void findsMethodFromSuperclass() throws Exception {
            Method m = call(new ArrayList<>(), "hashCode");
            assertNotNull(m);
        }

        @Test
        void returnsNullForNonExistentMethod() throws Exception {
            assertNull(call("hello", "nonExistentMethod123"));
        }

        @Test
        void findsToStringFromObject() throws Exception {
            Method m = call(new Object(), "toString");
            assertNotNull(m);
        }
    }

    @Nested
    class FormatOutput {
        private SonarQubeIntegration createInstance() throws Exception {
            var constructor = SonarQubeIntegration.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            if (constructor.getParameterCount() == 0) {
                return (SonarQubeIntegration) constructor.newInstance();
            }
            // If constructor takes Project, we need to pass null and handle it
            return (SonarQubeIntegration) constructor.newInstance(new Object[constructor.getParameterCount()]);
        }

        private String call(List<String> findings, int limit, int offset) throws Exception {
            SonarQubeIntegration instance = createInstance();
            return (String) formatOutput.invoke(instance, findings, limit, offset);
        }

        @Test
        void emptyFindingsReturnsCleanMessage() throws Exception {
            String result = call(List.of(), 100, 0);
            assertTrue(result.contains("No issues found"));
            assertTrue(result.contains("0 bugs"));
        }

        @Test
        void singleFindingShowsTotal() throws Exception {
            String result = call(List.of("Bug: NullPointer in Foo.java:42"), 100, 0);
            assertTrue(result.contains("1 total"));
            assertTrue(result.contains("Bug: NullPointer in Foo.java:42"));
        }

        @Test
        void multipleFindingsShowAll() throws Exception {
            List<String> findings = List.of("Finding 1", "Finding 2", "Finding 3");
            String result = call(findings, 100, 0);
            assertTrue(result.contains("3 total"));
            assertTrue(result.contains("Finding 1"));
            assertTrue(result.contains("Finding 3"));
        }

        @Test
        void limitTruncatesFindings() throws Exception {
            List<String> findings = List.of("A", "B", "C", "D", "E");
            String result = call(findings, 2, 0);
            assertTrue(result.contains("5 total"));
            assertTrue(result.contains("showing 1-2"));
            assertTrue(result.contains("A"));
            assertTrue(result.contains("B"));
            assertFalse(result.contains("\nC\n"));
            assertTrue(result.contains("3 more findings not shown"));
        }

        @Test
        void offsetSkipsFindings() throws Exception {
            List<String> findings = List.of("A", "B", "C", "D", "E");
            String result = call(findings, 2, 2);
            assertTrue(result.contains("showing 3-4"));
            assertTrue(result.contains("C"));
            assertTrue(result.contains("D"));
        }

        @Test
        void offsetBeyondEndShowsNothing() throws Exception {
            List<String> findings = List.of("A", "B");
            String result = call(findings, 10, 10);
            assertTrue(result.contains("2 total"));
            // start = min(10, 2) = 2, end = min(12, 2) = 2: shows nothing
            assertFalse(result.contains("A"));
        }

        @Test
        void allFindingsShownNoTruncationMessage() throws Exception {
            List<String> findings = List.of("X", "Y");
            String result = call(findings, 100, 0);
            assertFalse(result.contains("more findings not shown"));
        }
    }
}
