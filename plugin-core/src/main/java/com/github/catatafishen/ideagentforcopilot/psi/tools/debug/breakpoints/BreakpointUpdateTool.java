package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.*;
import org.jetbrains.annotations.NotNull;

public final class BreakpointUpdateTool extends DebugTool {

    public BreakpointUpdateTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_update";
    }

    @Override
    public @NotNull String displayName() {
        return "Update Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Enable/disable a breakpoint or update its condition and log expression by 1-based index (from breakpoint_list)";
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
                {"index", TYPE_INTEGER, "1-based breakpoint index from breakpoint_list"},
                {"enabled", TYPE_BOOLEAN, "Enable or disable the breakpoint"},
                {"condition", TYPE_STRING, "New condition expression, or empty string to clear"},
                {"log_expression", TYPE_STRING, "New log expression, or empty string to clear"},
                {"suspend", TYPE_BOOLEAN, "Whether to suspend on hit"},
        }, "index");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        int index = args.get("index").getAsInt();
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
                .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (index < 1 || index > all.length) {
            return "Breakpoint index " + index + " out of range (1–" + all.length + "). Run breakpoint_list to see current breakpoints.";
        }

        XBreakpoint<?> bp = all[index - 1];
        PlatformApiCompat.writeActionRunAndWait(() -> {
            if (args.has("enabled")) bp.setEnabled(args.get("enabled").getAsBoolean());
            if (args.has("condition")) {
                String cond = args.get("condition").getAsString();
                bp.setConditionExpression(cond.isBlank() ? null : PlatformApiCompat.createXExpression(cond));
            }
            if (args.has("suspend")) {
                bp.setSuspendPolicy(args.get("suspend").getAsBoolean() ? SuspendPolicy.ALL : SuspendPolicy.NONE);
            }
            if (args.has("log_expression")) {
                String logExpr = args.get("log_expression").getAsString();
                bp.setLogExpressionObject(logExpr.isBlank() ? null : PlatformApiCompat.createXExpression(logExpr));
            }
        });

        return "Updated breakpoint #" + index + ".";
    }
}
