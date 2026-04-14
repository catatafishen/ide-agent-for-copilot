package com.github.catatafishen.agentbridge.memory.validation;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Listens for file changes (content, delete, move) and triggers revalidation
 * of memory entries whose evidence references the changed files.
 *
 * <p>Debounces changes: batches file paths for 5 seconds before triggering
 * validation, to avoid excessive work during rapid edits.</p>
 */
public final class MemoryStalenessTrigger implements BulkFileListener, Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryStalenessTrigger.class);
    private static final long DEBOUNCE_SECONDS = 5;

    private final Project project;
    private final Set<String> pendingPaths = new HashSet<>();
    private ScheduledFuture<?> debounceTask;
    private volatile boolean disposed;

    public MemoryStalenessTrigger(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Register this listener on the project message bus.
     */
    public void register(@NotNull Disposable parentDisposable) {
        project.getMessageBus()
            .connect(parentDisposable)
            .subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, this);
        LOG.info("MemoryStalenessTrigger registered for project: " + project.getName());
    }

    @Override
    public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        if (disposed) return;

        MemoryService memoryService = project.getService(MemoryService.class);
        if (memoryService == null || !memoryService.isActive()) return;

        String basePath = project.getBasePath();
        if (basePath == null) return;

        Set<String> changedPaths = new HashSet<>();
        for (VFileEvent event : events) {
            String relativePath = extractRelativePath(event, basePath);
            if (relativePath != null) {
                changedPaths.add(relativePath);
            }
        }

        if (changedPaths.isEmpty()) return;

        synchronized (pendingPaths) {
            pendingPaths.addAll(changedPaths);
            scheduleDebouncedValidation();
        }
    }

    private void scheduleDebouncedValidation() {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        debounceTask = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(this::processPendingPaths, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
    }

    private void processPendingPaths() {
        if (disposed) return;

        Set<String> paths;
        synchronized (pendingPaths) {
            if (pendingPaths.isEmpty()) return;
            paths = new HashSet<>(pendingPaths);
            pendingPaths.clear();
        }

        MemoryService memoryService = project.getService(MemoryService.class);
        if (memoryService == null || !memoryService.isActive()) return;

        var store = memoryService.getStore();
        if (store == null) return;

        int totalOutcomes = 0;
        for (String path : paths) {
            var outcomes = MemoryValidator.validateByFile(project, store, path);
            totalOutcomes += outcomes.size();
        }

        if (totalOutcomes > 0) {
            LOG.info("Staleness trigger: revalidated " + totalOutcomes
                + " memories for " + paths.size() + " changed file(s)");
        }
    }

    private static String extractRelativePath(@NotNull VFileEvent event, @NotNull String basePath) {
        VirtualFile file = switch (event) {
            case VFileContentChangeEvent change -> change.getFile();
            case VFileDeleteEvent delete -> delete.getFile();
            case VFileMoveEvent move -> move.getFile();
            default -> null;
        };

        if (file == null || file.isDirectory()) return null;
        String path = file.getPath();
        if (!path.startsWith(basePath)) return null;

        // Skip non-source files
        String name = file.getName();
        if (name.startsWith(".") || path.contains("/build/") || path.contains("/out/")
            || path.contains("/node_modules/") || path.contains("/.agent-work/")) {
            return null;
        }

        return path.substring(basePath.length() + 1);
    }

    @Override
    public void dispose() {
        disposed = true;
        if (debounceTask != null) {
            debounceTask.cancel(true);
        }
        synchronized (pendingPaths) {
            pendingPaths.clear();
        }
    }
}
