package com.github.catatafishen.agentbridge.memory.layers;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for L2 ({@link OnDemandLayer}).
 */
class OnDemandLayerTest {

    private static final String WING = "test-project";

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(MemoryStore.class);
    }

    @Test
    void layerIdReturnsL2OnDemand() {
        OnDemandLayer layer = new OnDemandLayer(store);
        assertEquals("L2-on-demand", layer.layerId());
    }

    @Test
    void displayNameReturnsOnDemandRecall() {
        OnDemandLayer layer = new OnDemandLayer(store);
        assertEquals("On-Demand Recall", layer.displayName());
    }

    @Test
    void renderWithEmptyResultsReturnsEmpty() throws IOException {
        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(Collections.emptyList());

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);

        assertEquals("", result);
    }

    @Test
    void renderWithResultsFormatsMarkdown() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer1")
            .wing(WING)
            .room("codebase")
            .content("Implemented retry logic for HTTP client")
            .memoryType("solution")
            .filedAt(Instant.parse("2024-02-10T14:00:00Z"))
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 1.0f);

        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(List.of(searchResult));

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "codebase");

        assertTrue(result.startsWith("## On-Demand Recall"));
        assertTrue(result.contains("[solution]"));
        assertTrue(result.contains("codebase:"));
        assertTrue(result.contains("Implemented retry logic for HTTP client"));
    }

    @Test
    void renderWithQueryIncludesQueryInHeading() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-q")
            .wing(WING)
            .room("debugging")
            .content("Fixed NPE in data pipeline")
            .memoryType("problem")
            .filedAt(Instant.now())
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 0.8f);

        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(List.of(searchResult));

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "debugging");

        assertTrue(result.contains("## On-Demand Recall — debugging"));
    }

    @Test
    void renderWithoutQueryHeadingHasNoSuffix() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-noq")
            .wing(WING)
            .room("general")
            .content("General project notes")
            .memoryType("general")
            .filedAt(Instant.now())
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 0.5f);

        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(List.of(searchResult));

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);

        assertTrue(result.startsWith("## On-Demand Recall\n\n"));
        assertFalse(result.contains("—"));
    }

    @Test
    void renderTruncatesContentOver300Chars() throws IOException {
        String longContent = "B".repeat(400);
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-long")
            .wing(WING)
            .room("general")
            .content(longContent)
            .memoryType("context")
            .filedAt(Instant.now())
            .build();
        DrawerDocument.SearchResult searchResult = new DrawerDocument.SearchResult(drawer, 0.9f);

        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(List.of(searchResult));

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);

        assertFalse(result.contains(longContent));
        assertTrue(result.contains("B".repeat(300) + "…"));
    }

    @Test
    void renderWhenStoreThrowsIOExceptionReturnsEmpty() throws IOException {
        when(store.search(any(MemoryQuery.class), isNull())).thenThrow(new IOException("index corrupted"));

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "codebase");

        assertEquals("", result);
    }

    @Test
    void customLimitIsRespected() throws IOException {
        int customLimit = 3;
        when(store.search(any(MemoryQuery.class), isNull())).thenReturn(Collections.emptyList());

        OnDemandLayer layer = new OnDemandLayer(store, customLimit);
        layer.render(WING, null);

        // Verify that the MemoryQuery passed to search had the custom limit
        verify(store).search(
            org.mockito.ArgumentMatchers.argThat(mq -> mq.limit() == customLimit),
            isNull()
        );
    }
}
