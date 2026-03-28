package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.breakpoints.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class BreakpointAddTool extends DebugTool {

    public BreakpointAddTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_add";
    }

    @Override
    public @NotNull String displayName() {
        return "Add Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Add a line breakpoint with optional condition, log expression, and suspend control";
    }

    @Override
    public @NotNull String kind() {
        return "write";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "File path (absolute or project-relative)"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {"condition", TYPE_STRING, "Optional condition expression (breakpoint only fires when true)"},
            {"log_expression", TYPE_STRING, "Optional log expression (non-suspending log breakpoint)"},
            {"enabled", TYPE_BOOLEAN, "Whether the breakpoint is enabled (default: true)"},
            {"suspend", TYPE_BOOLEAN, "Whether to suspend execution on hit (default: true). Set false with log_expression for a tracepoint."},
        }, "file", "line");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String path = args.get("file").getAsString();
        int lineZeroBased = args.get("line").getAsInt() - 1;
        String condition = args.has("condition") ? args.get("condition").getAsString() : null;
        String logExpr = args.has("log_expression") ? args.get("log_expression").getAsString() : null;
        boolean enabled = !args.has("enabled") || args.get("enabled").getAsBoolean();
        boolean suspend = !args.has("suspend") || args.get("suspend").getAsBoolean();

        VirtualFile file = refreshAndFindVirtualFile(path);
        if (file == null) return "File not found: " + path;

        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();

        // Check for duplicate
        XLineBreakpoint<?> existing = findLineBreakpoint(mgr, file, lineZeroBased);
        if (existing != null) {
            return "Breakpoint already exists at " + file.getName() + ':' + (lineZeroBased + 1) + ". Use breakpoint_update to modify it.";
        }

        // Add via XDebuggerUtil (handles type detection per language)
        PlatformApiCompat.writeActionRunAndWait(() ->
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineZeroBased, false));

        XLineBreakpoint<?> bp = findLineBreakpoint(mgr, file, lineZeroBased);
        if (bp == null) return "Failed to add breakpoint — the file or line may not support breakpoints.";

        // Apply properties
        final String finalCondition = condition;
        final String finalLogExpr = logExpr;
        PlatformApiCompat.writeActionRunAndWait(() -> {
            bp.setEnabled(enabled);
            if (finalCondition != null && !finalCondition.isBlank()) {
                bp.setConditionExpression(PlatformApiCompat.createXExpression(finalCondition));
            }
            if (finalLogExpr != null && !finalLogExpr.isBlank()) {
                bp.setLogExpressionObject(PlatformApiCompat.createXExpression(finalLogExpr));
            }
            bp.setSuspendPolicy(suspend ? SuspendPolicy.ALL : SuspendPolicy.NONE);
        });

        return "Added breakpoint at " + file.getName() + ':' + (lineZeroBased + 1)
            + (condition != null ? " [condition: " + condition + "]" : "")
            + (logExpr != null ? " [log: " + logExpr + "]" : "")
            + (enabled ? "" : " [disabled]")
            + (suspend ? "" : " [non-suspending]");
    }

    @Nullable
    private static XLineBreakpoint<?> findLineBreakpoint(
        XBreakpointManager mgr, VirtualFile file, int line) {
        return ApplicationManager.getApplication()
            .runReadAction((Computable<XLineBreakpoint<?>>) () -> {
                for (XBreakpoint<?> bp : mgr.getAllBreakpoints()) {
                    if (bp instanceof XLineBreakpoint<?> lbp
                        && lbp.getSourcePosition() != null
                        && file.equals(lbp.getSourcePosition().getFile())
                        && lbp.getLine() == line) {
                        return lbp;
                    }
                }
                return null;
            });
    }
}
