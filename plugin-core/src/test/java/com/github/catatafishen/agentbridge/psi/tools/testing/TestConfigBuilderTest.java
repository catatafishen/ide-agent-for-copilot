package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TestConfigBuilder} — pure config naming, FQN resolution,
 * and command parsing methods. No IDE dependencies.
 */
class TestConfigBuilderTest {

    @Nested
    @DisplayName("buildFqn")
    class BuildFqn {

        @Test
        @DisplayName("non-empty package prepends package with dot")
        void nonEmptyPackage() {
            assertEquals("com.example.MyTest", TestConfigBuilder.buildFqn("com.example", "MyTest"));
        }

        @Test
        @DisplayName("null package returns simple name only")
        void nullPackage() {
            assertEquals("MyTest", TestConfigBuilder.buildFqn(null, "MyTest"));
        }

        @Test
        @DisplayName("empty package returns simple name only")
        void emptyPackage() {
            assertEquals("MyTest", TestConfigBuilder.buildFqn("", "MyTest"));
        }

        @Test
        @DisplayName("deeply nested package builds correct FQN")
        void deeplyNestedPackage() {
            assertEquals("a.b.c.d.Foo", TestConfigBuilder.buildFqn("a.b.c.d", "Foo"));
        }
    }

    @Nested
    @DisplayName("extractFqnFromSourceText")
    class ExtractFqnFromSourceText {

        @Test
        @DisplayName("Java source with package and semicolon")
        void javaSourceWithPackage() {
            String source = "package com.example;\npublic class Foo {}";
            assertEquals("com.example.Foo", TestConfigBuilder.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("Kotlin source with package (no semicolon)")
        void kotlinSourceNoSemicolon() {
            String source = "package com.example\nclass Foo";
            assertEquals("com.example.Foo", TestConfigBuilder.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("no package declaration returns simple name")
        void noPackageDeclaration() {
            String source = "public class Foo {}";
            assertEquals("Foo", TestConfigBuilder.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("empty source returns simple name")
        void emptySource() {
            assertEquals("Foo", TestConfigBuilder.extractFqnFromSourceText("", "Foo"));
        }

        @Test
        @DisplayName("extra whitespace between keyword and package name")
        void extraWhitespace() {
            String source = "package   com.foo;\npublic class Foo {}";
            assertEquals("com.foo.Foo", TestConfigBuilder.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("package not on first line is not matched (regex uses ^)")
        void packageNotOnFirstLine() {
            String source = "// comment\npackage com.foo;\npublic class Foo {}";
            assertEquals("Foo", TestConfigBuilder.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("single-segment package name")
        void singleSegmentPackage() {
            String source = "package mypackage;\nclass Bar {}";
            assertEquals("mypackage.Bar", TestConfigBuilder.extractFqnFromSourceText(source, "Bar"));
        }
    }

    @Nested
    @DisplayName("buildJUnitConfigName")
    class BuildJUnitConfigName {

        @Test
        @DisplayName("class only produces 'Test: ClassName'")
        void classOnly() {
            assertEquals("Test: MyTest", TestConfigBuilder.buildJUnitConfigName("MyTest", null));
        }

        @Test
        @DisplayName("class with method produces 'Test: ClassName.methodName'")
        void classAndMethod() {
            assertEquals("Test: MyTest.testFoo", TestConfigBuilder.buildJUnitConfigName("MyTest", "testFoo"));
        }

        @Test
        @DisplayName("simple name with single-char method")
        void singleCharMethod() {
            assertEquals("Test: A.x", TestConfigBuilder.buildJUnitConfigName("A", "x"));
        }
    }

    @Nested
    @DisplayName("buildPatternConfigName")
    class BuildPatternConfigName {

        @Test
        @DisplayName("wildcard pattern with class count")
        void wildcardPattern() {
            assertEquals("Test: *Test (5 classes)", TestConfigBuilder.buildPatternConfigName("*Test", 5));
        }

        @Test
        @DisplayName("single matching class")
        void singleClass() {
            assertEquals("Test: *Integration* (1 classes)",
                    TestConfigBuilder.buildPatternConfigName("*Integration*", 1));
        }

        @Test
        @DisplayName("zero matching classes")
        void zeroClasses() {
            assertEquals("Test: *Nothing* (0 classes)",
                    TestConfigBuilder.buildPatternConfigName("*Nothing*", 0));
        }
    }

    @Nested
    @DisplayName("buildGradleTaskPrefix")
    class BuildGradleTaskPrefix {

        @Test
        @DisplayName("empty module returns empty prefix")
        void emptyModule() {
            assertEquals("", TestConfigBuilder.buildGradleTaskPrefix(""));
        }

        @Test
        @DisplayName("named module returns ':module:' prefix")
        void namedModule() {
            assertEquals(":plugin-core:", TestConfigBuilder.buildGradleTaskPrefix("plugin-core"));
        }

        @Test
        @DisplayName("simple module name")
        void simpleModule() {
            assertEquals(":app:", TestConfigBuilder.buildGradleTaskPrefix("app"));
        }
    }

    @Nested
    @DisplayName("buildGradleTestFilter")
    class BuildGradleTestFilter {

        @Test
        @DisplayName("simple class name gets wildcard package prefix")
        void simpleClassName() {
            assertEquals("*.FormattingTest", TestConfigBuilder.buildGradleTestFilter("FormattingTest"));
        }

        @Test
        @DisplayName("wildcard class suffix gets wildcard package prefix")
        void wildcardSuffix() {
            assertEquals("*.*Test", TestConfigBuilder.buildGradleTestFilter("*Test"));
        }

        @Test
        @DisplayName("double wildcard is unchanged")
        void doubleWildcard() {
            assertEquals("*.*Test", TestConfigBuilder.buildGradleTestFilter("*.*Test"));
        }

        @Test
        @DisplayName("fully qualified class name is unchanged")
        void fullyQualifiedClass() {
            assertEquals("com.example.FormattingTest",
                    TestConfigBuilder.buildGradleTestFilter("com.example.FormattingTest"));
        }

        @Test
        @DisplayName("class.method with no package gets wildcard package prefix")
        void classMethodNoPackage() {
            assertEquals("*.FormattingTest.testFoo",
                    TestConfigBuilder.buildGradleTestFilter("FormattingTest.testFoo"));
        }

        @Test
        @DisplayName("fully qualified class.method is unchanged")
        void fullyQualifiedClassMethod() {
            assertEquals("com.example.FormattingTest.testFoo",
                    TestConfigBuilder.buildGradleTestFilter("com.example.FormattingTest.testFoo"));
        }

        @Test
        @DisplayName("wildcard with package qualifier is unchanged")
        void wildcardWithPackage() {
            assertEquals("*.FormattingTest", TestConfigBuilder.buildGradleTestFilter("*.FormattingTest"));
        }

        @Test
        @DisplayName("single wildcard gets prefix")
        void singleWildcard() {
            assertEquals("*.*", TestConfigBuilder.buildGradleTestFilter("*"));
        }
    }

    @Nested
    @DisplayName("parseTestsFilterFromCommand")
    class ParseTestsFilterFromCommand {

        @Test
        @DisplayName("extracts --tests argument from Gradle command")
        void gradleTests() {
            assertEquals("com.example.MyTest",
                    TestConfigBuilder.parseTestsFilterFromCommand("./gradlew test --tests com.example.MyTest"));
        }

        @Test
        @DisplayName("extracts --tests with quoted argument")
        void gradleTestsQuoted() {
            assertEquals("com.example.MyTest",
                    TestConfigBuilder.parseTestsFilterFromCommand(
                            "./gradlew test --tests \"com.example.MyTest\""));
        }

        @Test
        @DisplayName("extracts -Dtest argument from Maven command")
        void mavenTests() {
            assertEquals("MyTest",
                    TestConfigBuilder.parseTestsFilterFromCommand("mvn test -Dtest=MyTest"));
        }

        @Test
        @DisplayName("returns null when no test filter present")
        void noFilter() {
            assertNull(TestConfigBuilder.parseTestsFilterFromCommand("./gradlew test"));
        }
    }

    @Nested
    @DisplayName("parseModuleFromCommand")
    class ParseModuleFromCommand {

        @Test
        @DisplayName("extracts module from :module:task notation")
        void moduleAndTask() {
            assertEquals("plugin-core",
                    TestConfigBuilder.parseModuleFromCommand("./gradlew :plugin-core:test"));
        }

        @Test
        @DisplayName("returns empty string for no module prefix")
        void noModule() {
            assertEquals("", TestConfigBuilder.parseModuleFromCommand("./gradlew test"));
        }

        @Test
        @DisplayName("returns empty string for bare task name")
        void bareTask() {
            assertEquals("",
                    TestConfigBuilder.parseModuleFromCommand("./gradlew unitTest --tests Foo"));
        }
    }

    @Nested
    @DisplayName("parseTaskFromCommand")
    class ParseTaskFromCommand {

        @Test
        @DisplayName("extracts task name from gradlew command")
        void gradlewTask() {
            assertEquals("unitTest", TestConfigBuilder.parseTaskFromCommand("./gradlew unitTest"));
        }

        @Test
        @DisplayName("extracts task name with module prefix")
        void taskWithModule() {
            assertEquals("unitTest",
                    TestConfigBuilder.parseTaskFromCommand("./gradlew :plugin-core:unitTest"));
        }

        @Test
        @DisplayName("extracts task name with following flags")
        void taskWithFlags() {
            assertEquals("unitTest",
                    TestConfigBuilder.parseTaskFromCommand("./gradlew unitTest --tests com.example.Foo"));
        }

        @Test
        @DisplayName("returns null for non-Gradle command")
        void nonGradleCommand() {
            assertNull(TestConfigBuilder.parseTaskFromCommand("mvn test"));
        }
    }
}
