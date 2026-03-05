package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks which files the agent has read/written during the current session.
 * Used by {@link AgentFileDecorator} to annotate files in the Project View.
 * <p>
 * Background tints persist until {@link #clear(Project)} (end of turn).
 * Active labels ("Agent reading" / "Agent editing") auto-expire after
 * {@link #LABEL_DURATION_MS} from the <em>last</em> access to that file.
 * <p>
 * A per-file generation counter ensures that only the most recently scheduled
 * expiry task actually removes the label — earlier tasks become no-ops.
 */
final class FileAccessTracker {

    enum AccessType {
        READ, WRITE, READ_WRITE
    }

    private static final long LABEL_DURATION_MS = 4000;

    private static final Map<String, AccessType> accessMap = new ConcurrentHashMap<>();
    private static final Map<String, String> activeLabels = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> labelGenerations = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-file-label-expiry");
                t.setDaemon(true);
                return t;
            });

    private FileAccessTracker() {
    }

    static void recordRead(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        String key = vf.getPath();
        accessMap.merge(key, AccessType.READ, FileAccessTracker::merge);
        activeLabels.put(key, "Agent reading");
        long gen = generationFor(key).incrementAndGet();
        refreshNode(project, vf);
        scheduleLabelExpiry(project, key, vf, gen);
    }

    static void recordWrite(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        String key = vf.getPath();
        accessMap.merge(key, AccessType.WRITE, FileAccessTracker::merge);
        activeLabels.put(key, "Agent editing");
        long gen = generationFor(key).incrementAndGet();
        refreshNode(project, vf);
        scheduleLabelExpiry(project, key, vf, gen);
    }

    /**
     * Returns the cumulative access type for the given file, or null if untouched.
     */
    static AccessType getAccess(VirtualFile vf) {
        return vf != null ? accessMap.get(vf.getPath()) : null;
    }

    /**
     * Returns the active label for the given file (e.g. "Agent reading"),
     * or null if the label has expired.
     */
    static String getActiveLabel(VirtualFile vf) {
        return vf != null ? activeLabels.get(vf.getPath()) : null;
    }

    static void clear(Project project) {
        accessMap.clear();
        activeLabels.clear();
        labelGenerations.clear();
        refreshProjectView(project);
    }

    private static AtomicLong generationFor(String key) {
        return labelGenerations.computeIfAbsent(key, k -> new AtomicLong());
    }

    private static AccessType merge(AccessType existing, AccessType incoming) {
        if (existing == AccessType.READ_WRITE || incoming == AccessType.READ_WRITE) {
            return AccessType.READ_WRITE;
        }
        if (existing != incoming) {
            return AccessType.READ_WRITE;
        }
        return existing;
    }

    private static void scheduleLabelExpiry(Project project, String key, VirtualFile vf, long generation) {
        scheduler.schedule(() -> {
            AtomicLong current = labelGenerations.get(key);
            if (current == null || current.get() != generation) {
                return; // a newer access superseded this one
            }
            activeLabels.remove(key);
            refreshNode(project, vf);
        }, LABEL_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Refreshes decoration for a single file node in the project view.
     * Uses {@code updateFrom()} to re-evaluate decorators for the specific node,
     * which is lighter than rebuilding the entire tree.
     */
    private static void refreshNode(Project project, VirtualFile vf) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                var pane = ProjectView.getInstance(project).getCurrentProjectViewPane();
                if (pane != null) {
                    pane.updateFrom(vf, false, false);
                }
            } catch (Exception ignored) {
                // Project view may not be available
            }
        });
    }

    /**
     * Refreshes all file decorations in the project view by rebuilding from root.
     * Used at end-of-turn when all highlights need to be cleared at once.
     */
    private static void refreshProjectView(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                var pane = ProjectView.getInstance(project).getCurrentProjectViewPane();
                if (pane != null) {
                    pane.updateFromRoot(false);
                }
            } catch (Exception ignored) {
                // Project view may not be available
            }
        });
    }
}
