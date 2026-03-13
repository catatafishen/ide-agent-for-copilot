package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level settings for project file shortcuts shown in the toolbar dropdown.
 * Each entry is a relative path (from project root) that appears in the Project Files menu.
 */
@Service(Service.Level.APP)
@State(name = "ProjectFilesSettings", storages = @Storage("ideAgentProjectFiles.xml"))
public final class ProjectFilesSettings implements PersistentStateComponent<ProjectFilesSettings.State> {

    private State myState = new State();

    public static ProjectFilesSettings getInstance() {
        return ApplicationManager.getApplication().getService(ProjectFilesSettings.class);
    }

    public List<FileEntry> getEntries() {
        return myState.entries;
    }

    public void setEntries(List<FileEntry> entries) {
        myState.entries = new ArrayList<>(entries);
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        private List<FileEntry> entries = getDefaults();

        public List<FileEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<FileEntry> entries) {
            this.entries = entries;
        }
    }

    /**
     * A single file shortcut entry.
     *
     * @see #getLabel()   Display name in the menu
     * @see #getPath()    Relative path from project root (supports glob for directory scanning)
     * @see #isGlob()     If true, path is a glob pattern (e.g., ".github/agents/*.md") and
     * matching files are listed individually in the menu
     */
    public static class FileEntry {
        private String label = "";
        private String path = "";
        private boolean glob = false;

        public FileEntry() {
        }

        public FileEntry(String label, String path, boolean isGlob) {
            this.label = label;
            this.path = path;
            this.glob = isGlob;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isGlob() {
            return glob;
        }

        public void setGlob(boolean glob) {
            this.glob = glob;
        }
    }

    public static List<FileEntry> getDefaults() {
        List<FileEntry> entries = new ArrayList<>();
        entries.add(new FileEntry("Instructions", ".copilot/copilot-instructions.md", false));
        entries.add(new FileEntry("Instructions (GitHub)", ".github/copilot-instructions.md", false));
        entries.add(new FileEntry("TODO", "TODO.md", false));
        entries.add(new FileEntry("Agent Definitions", ".github/agents/*.md", true));
        return entries;
    }
}
