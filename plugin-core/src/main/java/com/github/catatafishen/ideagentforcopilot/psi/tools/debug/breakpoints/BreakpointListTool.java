package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.breakpoints;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.breakpoints.*;
import org.jetbrains.annotations.NotNull;

public final class BreakpointListTool extends DebugTool {

    public BreakpointListTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_list";
    }

    @Override
    public @NotNull String displayName() {
        return "List Breakpoints";
    }

    @Override
    public @NotNull String description() {
        return "List all breakpoints with their conditions, enabled status, and source location";
    }

    @Override
    public @NotNull String kind() {
        return "read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();

        XBreakpoint<?>[] breakpoints = ApplicationManager.getApplication()
            .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (breakpoints.length == 0) return "No breakpoints set.";

        var sb = new StringBuilder();
        sb.append("Breakpoints (").append(breakpoints.length).append("):\n\n");
        int id = 1;
        for (XBreakpoint<?> bp : breakpoints) {
            sb.append('#').append(id++).append(": ");
            if (bp instanceof XLineBreakpoint<?> lbp) {
                String file = lbp.getShortFilePath();
                if (file == null && lbp.getSourcePosition() != null) {
                    file = lbp.getSourcePosition().getFile().getName();
                }
                sb.append(file != null ? file : "<unknown>")
                    .append(':').append(lbp.getLine() + 1);
            } else {
                sb.append(bp.getType().getTitle());
            }
            sb.append(" [").append(bp.isEnabled() ? "enabled" : "DISABLED").append("]");

            XExpression cond = bp.getConditionExpression();
            if (cond != null && !cond.getExpression().isBlank()) {
                sb.append("  condition: ").append(cond.getExpression());
            }
            XExpression logExpr = bp.getLogExpressionObject();
            if (logExpr != null && !logExpr.getExpression().isBlank()) {
                sb.append("  log: ").append(logExpr.getExpression());
            }

            SuspendPolicy suspend = bp.getSuspendPolicy();
            if (suspend == SuspendPolicy.NONE) sb.append("  [non-suspending]");

            sb.append('\n');
        }
        return sb.toString().strip();
    }
}
