package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which files the agent has read/written during the current session.
 * Used by {@link AgentFileDecorator} to annotate files in the Project View.
 */
final class FileAccessTracker {

    enum AccessType {
        READ, WRITE, READ_WRITE
    }

    private static final Map<String, AccessType> accessMap = new ConcurrentHashMap<>();

    private FileAccessTracker() {
    }

    static void recordRead(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        accessMap.merge(vf.getPath(), AccessType.READ, FileAccessTracker::merge);
    }

    static void recordWrite(Project project, String path) {
        VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return;
        accessMap.merge(vf.getPath(), AccessType.WRITE, FileAccessTracker::merge);
    }

    /**
     * Returns the access type for the given file, or null if untouched.
     */
    static AccessType getAccess(VirtualFile vf) {
        return vf != null ? accessMap.get(vf.getPath()) : null;
    }

    static void clear() {
        accessMap.clear();
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
}
