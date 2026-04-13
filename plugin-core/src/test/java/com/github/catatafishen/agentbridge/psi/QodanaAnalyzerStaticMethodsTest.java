package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the static SARIF-parsing helpers in {@link QodanaAnalyzer}.
 * These methods are package-private, so this test lives in the same package.
 */
class QodanaAnalyzerStaticMethodsTest {

    // ---------------------------------------------------------------
    // Helper: builds a minimal SARIF JSON wrapper
    // ---------------------------------------------------------------

    /**
     * Wraps one or more "run" objects into a top-level SARIF JSON string.
     */
    private static String sarifJson(JsonObject... runs) {
        JsonObject root = new JsonObject();
        JsonArray runsArray = new JsonArray();
        for (JsonObject run : runs) {
            runsArray.add(run);
        }
        root.add("runs", runsArray);
        return root.toString();
    }

    /**
     * Creates a run object with the given results array.
     */
    private static JsonObject runWith(JsonArray results) {
        JsonObject run = new JsonObject();
        run.add("results", results);
        return run;
    }

    /**
     * Creates a single SARIF result with all fields populated.
     */
    private static JsonObject sarifResult(String ruleId, String level, String message,
                                          String uri, int startLine) {
        JsonObject result = new JsonObject();
        result.addProperty("ruleId", ruleId);
        result.addProperty("level", level);

        JsonObject msg = new JsonObject();
        msg.addProperty("text", message);
        result.add("message", msg);

        JsonObject region = new JsonObject();
        region.addProperty("startLine", startLine);

        JsonObject artifactLoc = new JsonObject();
        artifactLoc.addProperty("uri", uri);

        JsonObject physLoc = new JsonObject();
        physLoc.add("artifactLocation", artifactLoc);
        physLoc.add("region", region);

        JsonObject loc = new JsonObject();
        loc.add("physicalLocation", physLoc);

        JsonArray locations = new JsonArray();
        locations.add(loc);
        result.add("locations", locations);

        return result;
    }

    // ---------------------------------------------------------------
    // parseSarifResults
    // ---------------------------------------------------------------

    @Nested
    class ParseSarifResultsTest {

        @Test
        void emptyRunsArray_returnsNoAnalysisRunsMessage() {
            String json = "{\"runs\":[]}";
            String result = QodanaAnalyzer.parseSarifResults(json, 100, null);
            assertTrue(result.contains("no analysis runs found"),
                "Expected 'no analysis runs found' but got: " + result);
        }

        @Test
        void nullRunsKey_returnsNoAnalysisRunsMessage() {
            String json = "{\"version\":\"2.1.0\"}";
            String result = QodanaAnalyzer.parseSarifResults(json, 100, null);
            assertTrue(result.contains("no analysis runs found"),
                "Expected 'no analysis runs found' but got: " + result);
        }

        @Test
        void singleProblem_formatsCorrectly() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("java:S1234", "error", "Null pointer", "src/Foo.java", 42));
            String json = sarifJson(runWith(results));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, null);

            assertTrue(output.contains("src/Foo.java"), "Should contain file path");
            assertTrue(output.contains("42"), "Should contain line number");
            assertTrue(output.contains("error"), "Should contain severity level");
            assertTrue(output.contains("java:S1234"), "Should contain rule id");
            assertTrue(output.contains("Null pointer"), "Should contain message");
        }

        @Test
        void multipleProblems_allFormatted() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("java:S1234", "error", "Issue one", "src/A.java", 10));
            results.add(sarifResult("java:S5678", "warning", "Issue two", "src/B.java", 20));
            results.add(sarifResult("java:S9999", "note", "Issue three", "src/C.java", 30));
            String json = sarifJson(runWith(results));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, null);

            assertTrue(output.contains("Issue one"));
            assertTrue(output.contains("Issue two"));
            assertTrue(output.contains("Issue three"));
            assertTrue(output.contains("3 problems"), "Summary should mention 3 problems");
        }

        @Test
        void limitApplied_truncatesResults() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "First", "src/A.java", 1));
            results.add(sarifResult("r2", "error", "Second", "src/B.java", 2));
            results.add(sarifResult("r3", "error", "Third", "src/C.java", 3));
            String json = sarifJson(runWith(results));

            String output = QodanaAnalyzer.parseSarifResults(json, 2, null);

            assertTrue(output.contains("First"));
            assertTrue(output.contains("Second"));
            assertFalse(output.contains("Third"), "Third problem should be truncated by limit=2");
            assertTrue(output.contains("2 problems"));
        }

        @Test
        void noProblems_returnsNoProblemsMessage() {
            JsonArray emptyResults = new JsonArray();
            String json = sarifJson(runWith(emptyResults));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, null);

            assertTrue(output.contains("no problems found"),
                "Expected 'no problems found' but got: " + output);
        }

        @Test
        void invalidJson_returnsParsingFailedMessage() {
            // The production code catches the parse exception and calls LOG.error(),
            // which IntelliJ's TestLoggerFactory treats as a test assertion failure.
            // We verify the error is about SARIF parsing.
            try {
                String output = QodanaAnalyzer.parseSarifResults("{invalid json!!!", 100, null);
                assertTrue(output.contains("parsing failed"),
                    "Expected 'parsing failed' but got: " + output);
            } catch (AssertionError e) {
                // TestLoggerFactory$TestLoggerAssertionError wraps the LOG.error() call
                assertTrue(e.getMessage() != null && e.getMessage().contains("SARIF"),
                    "Expected SARIF-related error but got: " + e.getMessage());
            }
        }

        @Test
        void multipleRuns_collectsFromAll() {
            JsonArray results1 = new JsonArray();
            results1.add(sarifResult("r1", "error", "From run 1", "src/A.java", 1));

            JsonArray results2 = new JsonArray();
            results2.add(sarifResult("r2", "warning", "From run 2", "src/B.java", 2));

            String json = sarifJson(runWith(results1), runWith(results2));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, null);

            assertTrue(output.contains("From run 1"), "Should have problem from first run");
            assertTrue(output.contains("From run 2"), "Should have problem from second run");
            assertTrue(output.contains("2 problems"));
        }

        @Test
        void fileCountInSummary_isCorrect() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "Issue A", "src/Foo.java", 10));
            results.add(sarifResult("r2", "error", "Issue B", "src/Foo.java", 20));
            results.add(sarifResult("r3", "error", "Issue C", "src/Bar.java", 5));
            String json = sarifJson(runWith(results));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, null);

            assertTrue(output.contains("3 problems"), "Should report 3 problems");
            assertTrue(output.contains("2 files"), "Should report 2 unique files");
        }

        @Test
        void basePath_relativizesFilePaths() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "Issue", "/home/user/project/src/Foo.java", 1));
            String json = sarifJson(runWith(results));

            String output = QodanaAnalyzer.parseSarifResults(json, 100, "/home/user/project");

            assertTrue(output.contains("src/Foo.java"), "Path should be relativized");
            assertFalse(output.contains("/home/user/project/src/Foo.java"),
                "Absolute path should not appear");
        }
    }

    // ---------------------------------------------------------------
    // collectSarifRunProblems
    // ---------------------------------------------------------------

    @Nested
    class CollectSarifRunProblemsTest {

        @Test
        void nullResultsArray_noCrash() {
            JsonObject run = new JsonObject();
            // no "results" key at all
            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 100, problems, files);

            assertTrue(problems.isEmpty(), "Should produce no problems");
            assertTrue(files.isEmpty(), "Should produce no files");
        }

        @Test
        void resultWithAllFields_addsFormattedProblem() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("java:S1234", "error", "Bad code", "src/Main.java", 42));
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 100, problems, files);

            assertEquals(1, problems.size());
            String problem = problems.get(0);
            assertTrue(problem.contains("src/Main.java"));
            assertTrue(problem.contains("42"));
            assertTrue(problem.contains("error"));
            assertTrue(problem.contains("java:S1234"));
            assertTrue(problem.contains("Bad code"));
        }

        @Test
        void missingRuleId_defaultsToUnknown() {
            JsonObject result = sarifResult("java:S1234", "error", "Msg", "src/A.java", 1);
            result.remove("ruleId");

            JsonArray results = new JsonArray();
            results.add(result);
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 100, problems, files);

            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("unknown"),
                "Missing ruleId should default to 'unknown'");
        }

        @Test
        void missingLevel_defaultsToWarning() {
            JsonObject result = sarifResult("r1", "error", "Msg", "src/A.java", 1);
            result.remove("level");

            JsonArray results = new JsonArray();
            results.add(result);
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 100, problems, files);

            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("warning"),
                "Missing level should default to 'warning'");
        }

        @Test
        void limitStopsCollection() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "First", "src/A.java", 1));
            results.add(sarifResult("r2", "error", "Second", "src/B.java", 2));
            results.add(sarifResult("r3", "error", "Third", "src/C.java", 3));
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 2, problems, files);

            assertEquals(2, problems.size(), "Should stop after 2 (limit)");
            assertTrue(problems.get(0).contains("First"));
            assertTrue(problems.get(1).contains("Second"));
        }

        @Test
        void addsFilePathsToSet() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "A", "src/Foo.java", 1));
            results.add(sarifResult("r2", "error", "B", "src/Bar.java", 2));
            results.add(sarifResult("r3", "error", "C", "src/Foo.java", 3));
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 100, problems, files);

            assertEquals(2, files.size(), "Should have 2 unique file paths");
            assertTrue(files.contains("src/Foo.java"));
            assertTrue(files.contains("src/Bar.java"));
        }

        @Test
        void preExistingProblems_limitCountsFromCurrentSize() {
            JsonArray results = new JsonArray();
            results.add(sarifResult("r1", "error", "New", "src/A.java", 1));
            results.add(sarifResult("r2", "error", "Also new", "src/B.java", 2));
            JsonObject run = runWith(results);

            List<String> problems = new ArrayList<>();
            problems.add("existing problem");
            Set<String> files = new HashSet<>();

            QodanaAnalyzer.collectSarifRunProblems(run, null, 2, problems, files);

            assertEquals(2, problems.size(),
                "Limit=2 and 1 pre-existing → should add only 1 more");
            assertEquals("existing problem", problems.get(0));
            assertTrue(problems.get(1).contains("New"));
        }
    }

    // ---------------------------------------------------------------
    // extractSarifMessage
    // ---------------------------------------------------------------

    @Nested
    class ExtractSarifMessageTest {

        @Test
        void hasMessageText_returnsText() {
            JsonObject result = new JsonObject();
            JsonObject msg = new JsonObject();
            msg.addProperty("text", "Something is wrong");
            result.add("message", msg);

            assertEquals("Something is wrong", QodanaAnalyzer.extractSarifMessage(result));
        }

        @Test
        void noMessageKey_returnsEmpty() {
            JsonObject result = new JsonObject();
            result.addProperty("ruleId", "r1");

            assertEquals("", QodanaAnalyzer.extractSarifMessage(result));
        }

        @Test
        void messageWithoutText_returnsEmpty() {
            JsonObject result = new JsonObject();
            JsonObject msg = new JsonObject();
            msg.addProperty("id", "some-id");
            result.add("message", msg);

            assertEquals("", QodanaAnalyzer.extractSarifMessage(result));
        }
    }

    // ---------------------------------------------------------------
    // extractSarifLocation
    // ---------------------------------------------------------------

    @Nested
    class ExtractSarifLocationTest {

        @Test
        void fullLocation_extractsFileAndLine() {
            JsonObject result = sarifResult("r1", "error", "msg", "src/main/java/Foo.java", 42);

            QodanaAnalyzer.SarifLocation loc = QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("src/main/java/Foo.java", loc.filePath());
            assertEquals(42, loc.line());
        }

        @Test
        void filePrefix_isStripped() {
            JsonObject result = sarifResult("r1", "error", "msg", "file:///home/user/src/Foo.java", 10);

            QodanaAnalyzer.SarifLocation loc = QodanaAnalyzer.extractSarifLocation(result, null);

            // file:// prefix is stripped → becomes /home/user/src/Foo.java
            assertFalse(loc.filePath().startsWith("file://"),
                "file:// prefix should be stripped, got: " + loc.filePath());
            assertTrue(loc.filePath().contains("/home/user/src/Foo.java"),
                "Path after stripping should be intact, got: " + loc.filePath());
        }

        @Test
        void basePath_relativization() {
            JsonObject result = sarifResult("r1", "error", "msg", "/home/user/project/src/Main.java", 5);

            QodanaAnalyzer.SarifLocation loc =
                QodanaAnalyzer.extractSarifLocation(result, "/home/user/project");

            assertEquals("src/Main.java", loc.filePath(),
                "Should relativize against basePath");
            assertEquals(5, loc.line());
        }

        @Test
        void noLocations_returnsEmptyPathAndNegativeLine() {
            JsonObject result = new JsonObject();
            result.addProperty("ruleId", "r1");

            QodanaAnalyzer.SarifLocation loc = QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("", loc.filePath());
            assertEquals(-1, loc.line());
        }

        @Test
        void emptyLocationsArray_returnsEmptyPathAndNegativeLine() {
            JsonObject result = new JsonObject();
            result.add("locations", new JsonArray());

            QodanaAnalyzer.SarifLocation loc = QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("", loc.filePath());
            assertEquals(-1, loc.line());
        }

        @Test
        void noPhysicalLocation_returnsEmptyPathAndNegativeLine() {
            JsonObject result = new JsonObject();
            JsonArray locations = new JsonArray();
            JsonObject loc = new JsonObject();
            loc.addProperty("logicalLocations", "some-value");
            locations.add(loc);
            result.add("locations", locations);

            QodanaAnalyzer.SarifLocation sarifLoc =
                QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("", sarifLoc.filePath());
            assertEquals(-1, sarifLoc.line());
        }

        @Test
        void noArtifactLocation_returnsEmptyPath() {
            JsonObject result = new JsonObject();
            JsonArray locations = new JsonArray();
            JsonObject loc = new JsonObject();
            JsonObject physLoc = new JsonObject();
            JsonObject region = new JsonObject();
            region.addProperty("startLine", 99);
            physLoc.add("region", region);
            // no artifactLocation
            loc.add("physicalLocation", physLoc);
            locations.add(loc);
            result.add("locations", locations);

            QodanaAnalyzer.SarifLocation sarifLoc =
                QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("", sarifLoc.filePath());
            assertEquals(99, sarifLoc.line());
        }

        @Test
        void noRegion_lineIsNegativeOne() {
            JsonObject result = new JsonObject();
            JsonArray locations = new JsonArray();
            JsonObject loc = new JsonObject();
            JsonObject physLoc = new JsonObject();
            JsonObject artifactLoc = new JsonObject();
            artifactLoc.addProperty("uri", "src/Test.java");
            physLoc.add("artifactLocation", artifactLoc);
            // no region
            loc.add("physicalLocation", physLoc);
            locations.add(loc);
            result.add("locations", locations);

            QodanaAnalyzer.SarifLocation sarifLoc =
                QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("src/Test.java", sarifLoc.filePath());
            assertEquals(-1, sarifLoc.line());
        }

        @Test
        void filePrefixWithBasePath_strippedThenRelativized() {
            JsonObject result = sarifResult("r1", "error", "msg",
                "file:///home/dev/project/src/App.java", 15);

            QodanaAnalyzer.SarifLocation loc =
                QodanaAnalyzer.extractSarifLocation(result, "/home/dev/project");

            assertEquals("src/App.java", loc.filePath(),
                "file:// should be stripped first, then relativized");
            assertEquals(15, loc.line());
        }

        @Test
        void nullBasePath_pathKeptAsIs() {
            JsonObject result = sarifResult("r1", "error", "msg", "src/Foo.java", 7);

            QodanaAnalyzer.SarifLocation loc =
                QodanaAnalyzer.extractSarifLocation(result, null);

            assertEquals("src/Foo.java", loc.filePath(),
                "With null basePath, path should remain unchanged");
            assertEquals(7, loc.line());
        }
    }
}
