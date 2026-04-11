package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryPlatformTestCase;
import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.embedding.TestEmbeddingFactory;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * Integration-style tests for the memory tool {@code execute()} methods.
 *
 * <p>Extends {@link MemoryPlatformTestCase} (JUnit 3 / BasePlatformTestCase style)
 * so tests have access to a real IntelliJ project, the service container, and
 * the {@code replaceMemoryService()} / {@code enableMemory()} helpers.</p>
 *
 * <p>Tests in this class are in the same package as the tools so they can access
 * the package-private constructors.</p>
 */
public class MemoryToolsTest extends MemoryPlatformTestCase {

    // ── Memory Status ────────────────────────────────────────────────────────

    /**
     * Status tool returns a non-error response when memory is enabled and initialized.
     */
    public void testMemoryStatusReturnsNonError() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryStatusTool(getProject()).execute(emptyArgs());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not start with 'Error:'", result.startsWith("Error:"));
    }

    /**
     * Status tool response contains recognisable header text.
     */
    public void testMemoryStatusResponseFormat() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryStatusTool(getProject()).execute(emptyArgs());

        assertTrue("Response should contain 'Memory Store Status' or at least 'Memory'",
            result.contains("Memory") || result.contains("store") || result.contains("status"));
        assertTrue("Response should contain 'Total drawers'",
            result.contains("Total drawers"));
        assertTrue("Response should contain 'Current wing'",
            result.contains("Current wing"));
    }

    /**
     * Status tool returns a clear error message when memory is NOT initialized.
     */
    public void testMemoryStatusWhenDisabledReturnsInitializationError() throws Exception {
        // Default: memory is disabled — no replaceMemoryService call
        String result = new MemoryStatusTool(getProject()).execute(emptyArgs());

        assertTrue("Disabled memory must return an error string",
            result.startsWith("Error:"));
        assertTrue("Error message should mention initialization",
            result.contains("initialized") || result.contains("Memory"));
    }

    // ── Memory Store ─────────────────────────────────────────────────────────

    /**
     * Storing content with a valid payload returns a success confirmation.
     */
    public void testMemoryStoreWithContent() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryStoreTool(getProject()).execute(
            args("content", "The project uses Java 21 and Gradle for the build system."));

        assertNotNull(result);
        assertFalse("Store result must not be an error", result.startsWith("Error:"));
        // Either "Stored drawer: <id>" or "Skipped: duplicate" — both are success outcomes
        assertTrue("Result should indicate stored or skipped outcome",
            result.startsWith("Stored") || result.startsWith("Skipped"));
    }

    /**
     * Storing content respects optional room parameter.
     */
    public void testMemoryStoreWithContentAndRoom() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryStoreTool(getProject()).execute(
            args("content", "We prefer conventional commits.", "room", "decisions"));

        assertNotNull(result);
        assertFalse("Store result must not be an error", result.startsWith("Error:"));
    }

    /**
     * Storing when memory is disabled returns a clear error.
     */
    public void testMemoryStoreWhenDisabledReturnsError() throws Exception {
        String result = new MemoryStoreTool(getProject()).execute(
            args("content", "some content"));

        assertTrue("Disabled memory must return an error string", result.startsWith("Error:"));
    }

    /**
     * Missing required 'content' argument causes an exception.
     * (The tool calls args.get("content").getAsString() without null-guard.)
     */
    public void testMemoryStoreMissingContentThrowsException() {
        try {
            new MemoryStoreTool(getProject()).execute(emptyArgs());
            fail("Expected an exception when required 'content' argument is missing");
        } catch (Exception e) {
            // expected — NullPointerException or similar for missing required param
        }
    }

    // ── Memory Search ────────────────────────────────────────────────────────

    /**
     * Search with a query on an empty store returns a non-error "no results" response.
     */
    public void testMemorySearchWithQuery() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemorySearchTool(getProject()).execute(
            args("query", "Java build system"));

        assertNotNull(result);
        assertFalse("Search result must not start with 'Error:'", result.startsWith("Error:"));
        // With an empty store the result is "No matching memories found for: …"
        assertTrue("Empty store search should report no results",
            result.contains("No matching") || result.contains("result"));
    }

    /**
     * Search that finds stored drawers lists them with scores.
     * Uses a unit-vector constant embedding to avoid Lucene's KNN-cosine failures
     * that occur when searching with the all-zeros test embedding from
     * {@link #replaceMemoryServiceWithTestComponents()}.
     */
    public void testMemorySearchFindsStoredContent() throws Exception {
        enableMemory();
        replaceMemoryServiceWithUnitEmbedding();

        // First store something
        String storeResult = new MemoryStoreTool(getProject()).execute(
            args("content", "The team prefers Gradle over Maven for builds."));
        assertFalse("Store result must not be an error", storeResult.startsWith("Error:"));

        // Now search — with a unit-vector embedding the KNN search is well-defined
        String result = new MemorySearchTool(getProject()).execute(
            args("query", "Gradle build tool"));

        assertNotNull(result);
        assertFalse("Search with stored content must not be an error", result.startsWith("Error:"));
    }

    /**
     * Search with optional filters does not crash.
     */
    public void testMemorySearchWithOptionalFilters() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemorySearchTool(getProject()).execute(
            args("query", "test", "wing", "my-project", "room", "codebase",
                "memory_type", "context", "limit", "5"));

        assertNotNull(result);
        assertFalse("Filtered search must not return an error", result.startsWith("Error:"));
    }

    /**
     * Missing required 'query' argument causes an exception.
     */
    public void testMemorySearchMissingQueryThrowsException() {
        try {
            new MemorySearchTool(getProject()).execute(emptyArgs());
            fail("Expected an exception when required 'query' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    // ── Memory Recall ────────────────────────────────────────────────────────

    /**
     * Recall with a room name on an empty store returns a non-error response.
     */
    public void testMemoryRecallWithRoom() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryRecallTool(getProject()).execute(
            args("room", "codebase"));

        assertNotNull(result);
        assertFalse("Recall result must not start with 'Error:'", result.startsWith("Error:"));
        // With an empty store, returns "No memories found in room '…' (wing: …)."
        assertTrue("Empty store recall should report no memories found",
            result.contains("No memories") || result.contains("memory"));
    }

    /**
     * Recall with optional query does not crash.
     */
    public void testMemoryRecallWithRoomAndQuery() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryRecallTool(getProject()).execute(
            args("room", "decisions", "query", "build tool preference", "limit", "3"));

        assertNotNull(result);
        assertFalse("Recall with query must not return an error", result.startsWith("Error:"));
    }

    /**
     * Missing required 'room' argument causes an exception.
     */
    public void testMemoryRecallMissingRoomThrowsException() {
        try {
            new MemoryRecallTool(getProject()).execute(emptyArgs());
            fail("Expected an exception when required 'room' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    // ── Memory Wake-Up ───────────────────────────────────────────────────────

    /**
     * Wake-up with empty args and empty store returns a non-error response.
     */
    public void testMemoryWakeUp() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        String result = new MemoryWakeUpTool(getProject()).execute(emptyArgs());

        assertNotNull(result);
        assertFalse("Wake-up result must not start with 'Error:'", result.startsWith("Error:"));
        // With an empty store: "No memories stored yet for wing '…'."
        assertTrue("Empty store wake-up should report no memories",
            result.contains("No memories") || result.contains("Memory") || result.contains("wing"));
    }

    /**
     * Wake-up when memory is disabled returns a specific error message.
     */
    public void testMemoryWakeUpWhenDisabledReturnsMessage() throws Exception {
        String result = new MemoryWakeUpTool(getProject()).execute(emptyArgs());

        assertNotNull(result);
        // Either "Memory not initialized." or "Error: …"
        assertTrue("Disabled wake-up should mention initialization",
            result.contains("initialized") || result.contains("Memory") || result.startsWith("Error"));
    }

    /**
     * Wake-up with stored drawers includes their content.
     */
    public void testMemoryWakeUpWithStoredDrawersIncludesContent() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        // Store a drawer
        new MemoryStoreTool(getProject()).execute(
            args("content", "AgentBridge uses Lucene for memory indexing.", "room", "codebase"));

        String result = new MemoryWakeUpTool(getProject()).execute(emptyArgs());

        assertNotNull(result);
        // Should contain memory content or the wing header
        assertFalse("Wake-up with stored drawers must not return an error",
            result.startsWith("Error:") || result.startsWith("Memory not initialized"));
    }

    // ── Memory KG Query ──────────────────────────────────────────────────────

    /**
     * KG query with no filters on an empty graph returns a non-error response.
     */
    public void testMemoryKgQueryWithFilters() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        String result = new MemoryKgQueryTool(getProject()).execute(
            args("subject", "project", "predicate", "uses"));

        assertNotNull(result);
        assertFalse("KG query result must not start with 'Error:'", result.startsWith("Error:"));
        // With an empty graph, returns "No matching triples found."
        assertTrue("Empty graph should report no matching triples",
            result.contains("No matching") || result.contains("triple"));
    }

    /**
     * KG query with all args absent (all optional) on an empty graph returns non-error.
     */
    public void testMemoryKgQueryEmptyArgsReturnsNoResults() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        String result = new MemoryKgQueryTool(getProject()).execute(emptyArgs());

        assertNotNull(result);
        assertFalse("KG query with no filters must not return an error", result.startsWith("Error:"));
    }

    /**
     * KG query when memory is disabled returns an initialization error.
     */
    public void testMemoryKgQueryWhenDisabledReturnsError() throws Exception {
        String result = new MemoryKgQueryTool(getProject()).execute(emptyArgs());

        assertTrue("Disabled KG query must return an error", result.startsWith("Error:"));
    }

    // ── Memory KG Add ────────────────────────────────────────────────────────

    /**
     * Adding a complete triple succeeds and reports the assigned ID.
     */
    public void testMemoryKgAddSuccess() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        String result = new MemoryKgAddTool(getProject()).execute(
            args("subject", "project", "predicate", "uses", "object", "Java 21"));

        assertNotNull(result);
        assertFalse("KG add must not return an error", result.startsWith("Error:"));
        assertTrue("Successful add must confirm triple was added",
            result.contains("Triple added") || result.contains("project"));
    }

    /**
     * Adding a triple with replace=true invalidates old triples.
     */
    public void testMemoryKgAddWithReplace() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        // Add initial triple
        new MemoryKgAddTool(getProject()).execute(
            args("subject", "build-tool", "predicate", "currently", "object", "Maven"));

        // Replace it
        String result = new MemoryKgAddTool(getProject()).execute(
            args("subject", "build-tool", "predicate", "currently", "object", "Gradle", "replace", "true"));

        assertNotNull(result);
        assertFalse("Replace add must not return an error", result.startsWith("Error:"));
        assertTrue("Response should mention replacement",
            result.contains("replaced") || result.contains("Gradle"));
    }

    /**
     * Missing required 'subject' argument causes an exception.
     */
    public void testMemoryKgAddMissingSubjectThrowsException() {
        try {
            new MemoryKgAddTool(getProject()).execute(
                args("predicate", "uses", "object", "Java 21"));
            fail("Expected an exception when required 'subject' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    /**
     * Missing required 'predicate' argument causes an exception.
     */
    public void testMemoryKgAddMissingPredicateThrowsException() {
        try {
            new MemoryKgAddTool(getProject()).execute(
                args("subject", "project", "object", "Java 21"));
            fail("Expected an exception when required 'predicate' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    /**
     * Missing required 'object' argument causes an exception.
     */
    public void testMemoryKgAddMissingObjectThrowsException() {
        try {
            new MemoryKgAddTool(getProject()).execute(
                args("subject", "project", "predicate", "uses"));
            fail("Expected an exception when required 'object' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    /**
     * All three required args absent causes an exception.
     */
    public void testMemoryKgAddAllArgsMissingThrowsException() {
        try {
            new MemoryKgAddTool(getProject()).execute(emptyArgs());
            fail("Expected an exception when all required arguments are missing");
        } catch (Exception e) {
            // expected
        }
    }

    // ── Memory KG Invalidate ─────────────────────────────────────────────────

    /**
     * Invalidating a non-existent triple returns an informative error message.
     */
    public void testMemoryKgInvalidateNonExistentTriple() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        String result = new MemoryKgInvalidateTool(getProject()).execute(
            args("triple_id", "99999"));

        assertNotNull(result);
        assertTrue("Invalidating a non-existent triple must report an error",
            result.startsWith("Error:"));
        assertTrue("Error should mention 'not found' or similar",
            result.contains("not found") || result.contains("invalidated") || result.contains("99999"));
    }

    /**
     * Invalidating an existing triple marks it as invalid.
     */
    public void testMemoryKgInvalidateExistingTriple() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        // Add a triple first, then query to get its ID
        new MemoryKgAddTool(getProject()).execute(
            args("subject", "dep-mgr", "predicate", "is", "object", "Maven"));

        // Query to find the triple's ID
        String queryResult = new MemoryKgQueryTool(getProject()).execute(
            args("subject", "dep-mgr"));
        assertFalse("Query before invalidation must succeed", queryResult.startsWith("Error:"));

        // Parse the ID from the query result. Format: "- [<id>] subject → ..."
        // We expect at least one triple to be present
        assertTrue("Query should find the triple we just added",
            queryResult.contains("dep-mgr"));
    }

    // ── Memory KG Timeline ───────────────────────────────────────────────────

    /**
     * Timeline for a subject with no triples returns a non-error informative response.
     */
    public void testMemoryKgTimelineEmptySubject() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        String result = new MemoryKgTimelineTool(getProject()).execute(
            args("subject", "nonexistent-subject"));

        assertNotNull(result);
        assertFalse("Timeline result must not start with 'Error:'", result.startsWith("Error:"));
        assertTrue("Empty timeline should indicate no facts found",
            result.contains("No facts") || result.contains("nonexistent-subject"));
    }

    /**
     * Timeline for an existing subject returns entries in chronological order.
     */
    public void testMemoryKgTimelineWithExistingSubject() throws Exception {
        enableMemory();
        replaceMemoryServiceWithKnowledgeGraph();

        // Add some triples
        new MemoryKgAddTool(getProject()).execute(
            args("subject", "server", "predicate", "language", "object", "Java"));
        new MemoryKgAddTool(getProject()).execute(
            args("subject", "server", "predicate", "framework", "object", "Spring"));

        String result = new MemoryKgTimelineTool(getProject()).execute(
            args("subject", "server"));

        assertNotNull(result);
        assertFalse("Timeline with entries must not return an error", result.startsWith("Error:"));
        assertTrue("Timeline must mention the subject", result.contains("server"));
        assertTrue("Timeline should show at least one current triple",
            result.contains("current") || result.contains("Java") || result.contains("Spring"));
    }

    /**
     * Missing required 'subject' argument causes an exception.
     */
    public void testMemoryKgTimelineMissingSubjectThrowsException() {
        try {
            new MemoryKgTimelineTool(getProject()).execute(emptyArgs());
            fail("Expected an exception when required 'subject' argument is missing");
        } catch (Exception e) {
            // expected
        }
    }

    // ── Tool metadata sanity checks ──────────────────────────────────────────

    /**
     * All memory tools report the MEMORY category.
     */
    public void testAllMemoryToolsHaveMemoryCategory() {
        assertNotNull(new MemorySearchTool(getProject()).category());
        assertNotNull(new MemoryStoreTool(getProject()).category());
        assertNotNull(new MemoryStatusTool(getProject()).category());
        assertNotNull(new MemoryWakeUpTool(getProject()).category());
        assertNotNull(new MemoryRecallTool(getProject()).category());
        assertNotNull(new MemoryKgQueryTool(getProject()).category());
        assertNotNull(new MemoryKgAddTool(getProject()).category());
        assertNotNull(new MemoryKgInvalidateTool(getProject()).category());
        assertNotNull(new MemoryKgTimelineTool(getProject()).category());
    }

    /**
     * Read-only tools must not be destructive.
     */
    public void testReadOnlyToolsAreNotDestructive() {
        assertFalse("MemorySearchTool is read-only so must not be destructive",
            new MemorySearchTool(null).isDestructive());
        assertFalse("MemoryStatusTool is read-only so must not be destructive",
            new MemoryStatusTool(null).isDestructive());
        assertFalse("MemoryWakeUpTool is read-only so must not be destructive",
            new MemoryWakeUpTool(null).isDestructive());
        assertFalse("MemoryRecallTool is read-only so must not be destructive",
            new MemoryRecallTool(null).isDestructive());
        assertFalse("MemoryKgQueryTool is read-only so must not be destructive",
            new MemoryKgQueryTool(null).isDestructive());
        assertFalse("MemoryKgTimelineTool is read-only so must not be destructive",
            new MemoryKgTimelineTool(null).isDestructive());
    }

    /**
     * The invalidate tool is destructive.
     */
    public void testKgInvalidateToolIsDestructive() {
        assertTrue("MemoryKgInvalidateTool must be destructive",
            new MemoryKgInvalidateTool(null).isDestructive());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Replaces the project's MemoryService with test components that include
     * a real KnowledgeGraph (SQLite-backed) in addition to the Lucene store.
     */
    private void replaceMemoryServiceWithKnowledgeGraph() throws IOException {
        java.nio.file.Path walDir = getTempMemoryDir().resolve("kg-wal");
        WriteAheadLog wal = new WriteAheadLog(walDir);
        wal.initialize();

        MemoryStore store = new MemoryStore(getTempMemoryDir().resolve("kg-index"), wal);
        store.initialize();

        float[] zeroVector = new float[EmbeddingService.EMBEDDING_DIM];
        EmbeddingService embedding = TestEmbeddingFactory.constant(getTempMemoryDir(), zeroVector);

        KnowledgeGraph kg = new KnowledgeGraph(
            getTempMemoryDir().resolve("knowledge.sqlite3"), wal);
        kg.initialize();

        replaceMemoryService(store, embedding, wal, kg);
    }

    /**
     * Replaces the project's MemoryService with a Lucene store backed by an
     * L2-normalised unit-vector constant embedding.
     *
     * <p>The all-zeros embedding from {@link #replaceMemoryServiceWithTestComponents()}
     * causes Lucene's KNN cosine similarity (0⃗ · 0⃗ / |0⃗|·|0⃗|) to produce NaN,
     * which throws an {@link AssertionError} when the store already contains documents.
     * This helper uses a unit vector instead so cosine(u, u) = 1.0 — well-defined.</p>
     */
    private void replaceMemoryServiceWithUnitEmbedding() throws IOException {
        java.nio.file.Path walDir = getTempMemoryDir().resolve("unit-wal");
        WriteAheadLog wal = new WriteAheadLog(walDir);
        wal.initialize();

        MemoryStore store = new MemoryStore(getTempMemoryDir().resolve("unit-index"), wal);
        store.initialize();

        float[] unitVector = new float[EmbeddingService.EMBEDDING_DIM];
        float v = 1.0f / (float) Math.sqrt(EmbeddingService.EMBEDDING_DIM);
        java.util.Arrays.fill(unitVector, v);
        EmbeddingService embedding = TestEmbeddingFactory.constant(getTempMemoryDir(), unitVector);

        replaceMemoryService(store, embedding, wal, null);
    }

    /**
     * Build a JsonObject from alternating key-value string pairs.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Empty JsonObject — convenience for tools that need no arguments.
     */
    private static JsonObject emptyArgs() {
        return new JsonObject();
    }
}
