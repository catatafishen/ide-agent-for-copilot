package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application-level settings for scratch file language-to-extension mappings.
 * Users can add/remove/edit which languages are available when opening
 * code blocks as scratch files from the chat UI.
 */
@Service(Service.Level.APP)
@State(name = "ScratchTypeSettings", storages = @Storage("ideAgentScratchTypes.xml"))
public final class ScratchTypeSettings implements PersistentStateComponent<ScratchTypeSettings.State> {

    private State myState = new State();

    public static ScratchTypeSettings getInstance() {
        return ApplicationManager.getApplication().getService(ScratchTypeSettings.class);
    }

    /**
     * Returns the full alias→extension map. Each key is a lowercase language
     * alias (e.g., "kotlin", "kt", "kts") and the value is the file extension
     * (e.g., "kt").
     */
    public Map<String, String> getMappings() {
        return myState.mappings;
    }

    public void setMappings(Map<String, String> mappings) {
        myState.mappings = new LinkedHashMap<>(mappings);
    }

    /**
     * Resolves a language name to a file extension using the configured mappings.
     * Falls back to the input (or "txt" if empty) when no mapping is found.
     */
    public String resolve(String language) {
        String ext = myState.mappings.get(language.toLowerCase());
        if (ext != null) return ext;
        return language.isEmpty() ? "txt" : language;
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
        public Map<String, String> mappings = getDefaults();
    }

    /**
     * Default language-to-extension mappings matching the original hardcoded list.
     */
    public static Map<String, String> getDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java", "java");
        m.put("kotlin", "kt");
        m.put("kt", "kt");
        m.put("kts", "kt");
        m.put("python", "py");
        m.put("py", "py");
        m.put("javascript", "js");
        m.put("js", "js");
        m.put("typescript", "ts");
        m.put("ts", "ts");
        m.put("tsx", "tsx");
        m.put("jsx", "jsx");
        m.put("html", "html");
        m.put("css", "css");
        m.put("xml", "xml");
        m.put("json", "json");
        m.put("yaml", "yaml");
        m.put("yml", "yaml");
        m.put("sql", "sql");
        m.put("shell", "sh");
        m.put("bash", "sh");
        m.put("sh", "sh");
        m.put("zsh", "sh");
        m.put("groovy", "groovy");
        m.put("scala", "scala");
        m.put("rust", "rs");
        m.put("rs", "rs");
        m.put("go", "go");
        m.put("golang", "go");
        m.put("c", "c");
        m.put("cpp", "cpp");
        m.put("c++", "cpp");
        m.put("ruby", "rb");
        m.put("rb", "rb");
        m.put("swift", "swift");
        m.put("php", "php");
        m.put("r", "r");
        m.put("markdown", "md");
        m.put("md", "md");
        m.put("toml", "toml");
        m.put("properties", "properties");
        m.put("gradle", "gradle");
        return m;
    }
}
