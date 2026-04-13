package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryQuery;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for L3 ({@link DeepSearchLayer}).
 */
class DeepSearchLayerTest {

    private static final String WING = "test-project";
    private static final float[] TEST_EMBEDDING = new float[]{0.1f, 0.2f, 0.3f};

    private MemoryStore store;
    private Embedder embedder;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(MemoryStore.class);
        embedder = Mockito.mock(Embedder.class);
    }

    @Test
    void layerIdReturnsL3DeepSearch() {
        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        assertEquals("L3-deep-search", layer.layerId());
    }

    @Test
    void displayNameReturnsDeepSearch() {
        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        assertEquals("Deep Search", layer.displayName());
    }

    @Test
    void renderWithNullQueryReturnsEmpty() {
        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, null);

        assertEquals("", result);
    }

    @Test
    void renderWithEmptyQueryReturnsEmpty() {
        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "");

        assertEquals("", result);
    }

    @Test
    void renderWithResultsFormatsMarkdownWithScores() throws Exception {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-deep1")
            .wing(WING)
            .room("codebase")
            .content("Implemented vector search with Lucene KNN")
            .memoryType("solution")
            .filedAt(Instant.parse("2024-03-01T09:00:00Z"))
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 0.95f);

        when(embedder.embed("vector search")).thenReturn(TEST_EMBEDDING);
        when(store.search(any(MemoryQuery.class), eq(TEST_EMBEDDING))).thenReturn(List.of(searchResult));

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "vector search");

        assertTrue(result.startsWith("## Deep Search Results\n\n"));
        assertTrue(result.contains("Semantic matches for: *vector search*"));
        assertTrue(result.contains("[0.95]"));
        assertTrue(result.contains("[solution]"));
        assertTrue(result.contains("codebase:"));
        assertTrue(result.contains("Implemented vector search with Lucene KNN"));
    }

    @Test
    void renderTruncatesContentOver300Chars() throws Exception {
        String longContent = "C".repeat(400);
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-deep-long")
            .wing(WING)
            .room("general")
            .content(longContent)
            .memoryType("context")
            .filedAt(Instant.now())
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 0.75f);

        when(embedder.embed("long query")).thenReturn(TEST_EMBEDDING);
        when(store.search(any(MemoryQuery.class), eq(TEST_EMBEDDING))).thenReturn(List.of(searchResult));

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "long query");

        assertFalse(result.contains(longContent));
        assertTrue(result.contains("C".repeat(300) + "…"));
    }

    @Test
    void renderWithNoResultsReturnsEmpty() throws Exception {
        when(embedder.embed("obscure query")).thenReturn(TEST_EMBEDDING);
        when(store.search(any(MemoryQuery.class), eq(TEST_EMBEDDING))).thenReturn(Collections.emptyList());

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "obscure query");

        assertEquals("", result);
    }

    @Test
    void renderWhenEmbedderThrowsReturnsEmpty() throws Exception {
        when(embedder.embed("fail query")).thenThrow(new RuntimeException("ONNX inference failed"));

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "fail query");

        assertEquals("", result);
    }

    @Test
    void renderWhenStoreThrowsReturnsEmpty() throws Exception {
        when(embedder.embed("store fail")).thenReturn(TEST_EMBEDDING);
        when(store.search(any(MemoryQuery.class), eq(TEST_EMBEDDING))).thenThrow(new IOException("index locked"));

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "store fail");

        assertEquals("", result);
    }

    @Test
    void scoreFormattingUsesTwoDecimalPlaces() throws Exception {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-score")
            .wing(WING)
            .room("decisions")
            .content("Architecture decision record")
            .memoryType("decision")
            .filedAt(Instant.now())
            .build();
        DrawerDocument.SearchResult result1 = new DrawerDocument.SearchResult(drawer, 0.8567f);

        when(embedder.embed("architecture")).thenReturn(TEST_EMBEDDING);
        when(store.search(any(MemoryQuery.class), eq(TEST_EMBEDDING))).thenReturn(List.of(result1));

        DeepSearchLayer layer = new DeepSearchLayer(store, embedder);
        String result = layer.render(WING, "architecture");

        // Score should be formatted as "0.86" (2 decimal places, rounded)
        assertTrue(result.contains("[0.86]"));
        assertFalse(result.contains("[0.8567]"));
    }
}
