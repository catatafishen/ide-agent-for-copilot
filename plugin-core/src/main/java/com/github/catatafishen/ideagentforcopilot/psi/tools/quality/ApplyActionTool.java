package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Invokes a named IDE quick-fix or intention action at a specific file and line.
 * Action names come from {@code get_highlights} or {@code get_available_actions} output
 * (the values listed after "→ Quick fixes:").
 *
 * <p>Unlike {@code apply_quickfix} (which requires an inspection ID and re-runs the inspection),
 * this tool uses the cached daemon highlight data and invokes the action directly via IntelliJ's
 * {@link IntentionAction} API — the same path the IDE takes when the user clicks the light-bulb.</p>
 */
public final class ApplyActionTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(ApplyActionTool.class);

    public ApplyActionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "apply_action";
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Action";
    }

    @Override
    public @NotNull String description() {
        return "Invoke a named IDE quick-fix or intention action at a specific file and line. "
            + "Action names come from get_highlights or get_available_actions output "
            + "(values listed after '→ Quick fixes:'). "
            + "Tip: use optimize_imports to fix all missing imports at once.";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {"action_name", TYPE_STRING, "Exact action name from get_highlights / get_available_actions output"}
        }, "file", "line", "action_name");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has("action_name")) {
            return "Error: 'file', 'line', and 'action_name' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String actionName = args.get("action_name").getAsString();

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(invokeAction(pathStr, targetLine, actionName));
            } catch (Exception e) {
                LOG.warn("Error invoking action '" + actionName + "' at " + pathStr + ":" + targetLine, e);
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = future.get(30, TimeUnit.SECONDS);
        if (result != null && !result.startsWith("Error") && !result.startsWith("No ")) {
            FileTool.followFileIfEnabled(project, pathStr, targetLine, targetLine,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied action");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    // ── Private helpers ──────────────────────────────────────

    private String invokeAction(String pathStr, int targetLine, String actionName) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        int lineStart = doc.getLineStartOffset(targetLine - 1);
        int lineEnd = doc.getLineEndOffset(targetLine - 1);

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        // Collect all highlights at the target line
        List<HighlightInfo> highlights = new ArrayList<>();
        DaemonCodeAnalyzerEx.processHighlights(doc, project, null, 0, doc.getTextLength(), h -> {
            if (h.getStartOffset() <= lineEnd && h.getEndOffset() >= lineStart) {
                highlights.add(h);
            }
            return true;
        });

        if (highlights.isEmpty()) {
            return "No highlights found at " + pathStr + " line " + targetLine
                + ". Open the file in the editor first, or call get_highlights to trigger analysis.";
        }

        // Find the named action across all highlights on the line
        MatchedAction match = findAction(highlights, actionName);
        if (match == null) {
            List<String> available = new ArrayList<>();
            for (var h : highlights) available.addAll(collectQuickFixNames(h));
            String hint = available.isEmpty() ? "none" : String.join(", ", available);
            return "Action '" + actionName + "' not found at " + pathStr + " line " + targetLine
                + ". Available: [" + hint + "]";
        }

        // Ensure an editor is open for the file (required by IntentionAction.invoke)
        Editor editor = getOrOpenEditor(vf);
        if (editor == null) {
            return "Error: Could not open editor for " + pathStr
                + ". Ensure the file is open in the IDE.";
        }

        if (!match.action().isAvailable(project, editor, psiFile)) {
            return "Action '" + actionName + "' is not currently applicable at " + pathStr
                + " line " + targetLine + ".";
        }

        IntentionAction action = match.action();
        WriteCommandAction.runWriteCommandAction(project, actionName, null,
            () -> action.invoke(project, editor, psiFile));

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Applied action: " + actionName + "\n  File: " + pathStr + " line " + targetLine;
    }

    /** Scans all highlights on the line and returns the first action whose text matches {@code name}. */
    @Nullable
    private MatchedAction findAction(List<HighlightInfo> highlights, String name) {
        for (var h : highlights) {
            IntentionAction found = h.findRegisteredQuickFix((descriptor, range) -> {
                IntentionAction a = descriptor.getAction();
                if (name.equals(a.getText())) return a;
                return null;
            });
            if (found != null) return new MatchedAction(found);
        }
        return null;
    }

    /** Returns an existing text editor for {@code vf}, opening it silently if necessary. */
    @Nullable
    private Editor getOrOpenEditor(VirtualFile vf) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        for (var fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor te) return te.getEditor();
        }
        // File not yet open — open it without stealing focus
        var opened = fem.openFile(vf, false);
        for (var fe : opened) {
            if (fe instanceof TextEditor te) return te.getEditor();
        }
        return null;
    }

    private record MatchedAction(IntentionAction action) {
    }
}
