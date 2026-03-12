package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Changes the IDE theme by name.
 */
public final class SetThemeTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(SetThemeTool.class);

    public SetThemeTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "set_theme";
    }

    @Override
    public @NotNull String displayName() {
        return "Set Theme";
    }

    @Override
    public @NotNull String description() {
        return "Change the IDE theme by name (e.g., 'Darcula', 'Light')";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Set theme: {theme}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"theme", TYPE_STRING, "Theme name or partial name (e.g., 'Darcula', 'Light')"}
        }, "theme");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("theme")) {
            return "Missing required parameter: 'theme' (theme name or partial name)";
        }
        String themeQuery = args.get("theme").getAsString();
        String queryLower = themeQuery.toLowerCase();

        var lafManager = LafManager.getInstance();
        var themes = kotlin.sequences.SequencesKt.toList(lafManager.getInstalledThemes());

        UIThemeLookAndFeelInfo target = null;
        for (var theme : themes) {
            if (theme.getName().equals(themeQuery)) {
                target = theme;
                break;
            }
            if (target == null && theme.getName().toLowerCase().contains(queryLower)) {
                target = theme;
            }
        }

        if (target == null) {
            return "Theme not found: '" + themeQuery + "'. Use list_themes to see available themes.";
        }

        var finalTarget = target;
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                lafManager.setCurrentLookAndFeel(finalTarget, false);
                lafManager.updateUI();
                resultFuture.complete("Theme changed to '" + finalTarget.getName() + "'.");
            } catch (Exception e) {
                LOG.warn("Failed to set theme", e);
                resultFuture.complete("Failed to set theme: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
