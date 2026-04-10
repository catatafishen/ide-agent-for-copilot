package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Mines all existing conversation sessions into the memory store.
 * Runs asynchronously on a background thread. Call {@link #run(Consumer)} to start.
 *
 * <p>This is intended to be triggered once when memory is first enabled, to backfill
 * memories from historical conversations. Duplicate detection in {@link com.github.catatafishen.agentbridge.memory.store.MemoryStore}
 * ensures idempotency — re-running is safe but wasteful.
 */
public final class BackfillMiner {

    private static final Logger LOG = Logger.getInstance(BackfillMiner.class);

    private final Project project;

    public BackfillMiner(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Mine all historical sessions asynchronously.
     *
     * @param progressCallback called on the background thread with progress messages
     *                         (e.g., "Mining session 3 of 15: Fix auth bug")
     * @return future completing with the aggregate result
     */
    public CompletableFuture<BackfillResult> run(@NotNull Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(
            () -> doBackfill(progressCallback),
            AppExecutorUtil.getAppExecutorService()
        );
    }

    private BackfillResult doBackfill(Consumer<String> progressCallback) {
        SessionStoreV2 sessionStore = SessionStoreV2.getInstance(project);
        String basePath = project.getBasePath();

        List<SessionStoreV2.SessionRecord> sessions = sessionStore.listSessions(basePath);
        if (sessions.isEmpty()) {
            progressCallback.accept("No sessions found to mine.");
            MemorySettings.getInstance(project).setBackfillCompleted(true);
            return new BackfillResult(0, 0, 0, 0, 0);
        }

        progressCallback.accept("Found " + sessions.size() + " sessions to mine.");

        TurnMiner miner = new TurnMiner(project);
        int totalSessions = sessions.size();
        int processedSessions = 0;
        int totalStored = 0;
        int totalFiltered = 0;
        int totalDuplicates = 0;
        int totalExchanges = 0;

        for (SessionStoreV2.SessionRecord session : sessions) {
            processedSessions++;
            String sessionLabel = session.name().isEmpty()
                ? session.id().substring(0, Math.min(8, session.id().length()))
                : session.name();
            progressCallback.accept("Mining session " + processedSessions + " of " + totalSessions
                + ": " + sessionLabel);

            try {
                List<EntryData> entries = sessionStore.loadEntriesBySessionId(basePath, session.id());
                if (entries == null || entries.isEmpty()) continue;

                TurnMiner.MineResult result = miner.mineTurn(entries, session.id(), session.agent()).join();
                totalStored += result.stored();
                totalFiltered += result.filtered();
                totalDuplicates += result.duplicates();
                totalExchanges += result.total();
            } catch (Exception e) {
                LOG.warn("Failed to mine session " + session.id(), e);
            }
        }

        MemorySettings.getInstance(project).setBackfillCompleted(true);

        String summary = "Backfill complete: " + totalStored + " memories stored from "
            + processedSessions + " sessions (" + totalDuplicates + " duplicates, "
            + totalFiltered + " filtered).";
        progressCallback.accept(summary);
        LOG.info(summary);

        return new BackfillResult(processedSessions, totalStored, totalFiltered, totalDuplicates, totalExchanges);
    }

    /**
     * @param sessions   number of sessions processed
     * @param stored     total drawers stored
     * @param filtered   total exchanges filtered out
     * @param duplicates total duplicate exchanges skipped
     * @param exchanges  total exchanges extracted
     */
    public record BackfillResult(int sessions, int stored, int filtered, int duplicates, int exchanges) {
    }
}
