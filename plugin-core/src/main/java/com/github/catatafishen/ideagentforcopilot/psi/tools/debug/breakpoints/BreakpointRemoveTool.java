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

public final class BreakpointRemoveTool extends DebugTool {

    public BreakpointRemoveTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "breakpoint_remove";
    }

    @Override
    public @NotNull String displayName() {
        return "Remove Breakpoint";
    }

    @Override
    public @NotNull String description() {
        return "Remove a breakpoint by index (from breakpoint_list), or remove all breakpoints";
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
                {"index", TYPE_INTEGER, "1-based breakpoint index from breakpoint_list. Omit (along with remove_all) to get current list."},
                {"remove_all", TYPE_BOOLEAN, "Set true to remove all breakpoints"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XBreakpointManager mgr = XDebuggerManager.getInstance(project).getBreakpointManager();
        XBreakpoint<?>[] all = ApplicationManager.getApplication()
                .runReadAction((Computable<XBreakpoint<?>[]>) mgr::getAllBreakpoints);

        if (args.has("remove_all") && args.get("remove_all").getAsBoolean()) {
            PlatformApiCompat.writeActionRunAndWait(() -> {
                for (XBreakpoint<?> bp : all) mgr.removeBreakpoint(bp);
            });
            return "Removed all " + all.length + " breakpoint(s).";
        }

        if (!args.has("index")) {
            return "Specify 'index' (1-based from breakpoint_list) or 'remove_all: true'. Currently " + all.length + " breakpoint(s) set.";
        }

        int index = args.get("index").getAsInt();
        if (index < 1 || index > all.length) {
            return "Breakpoint index " + index + " out of range (1–" + all.length + ").";
        }

        XBreakpoint<?> bp = all[index - 1];
        String desc = bp instanceof XLineBreakpoint<?> lbp
                ? (lbp.getSourcePosition() != null ? lbp.getSourcePosition().getFile().getName() + ':' + (lbp.getLine() + 1) : "#" + index)
                : bp.getType().getTitle();
        PlatformApiCompat.writeActionRunAndWait(() -> mgr.removeBreakpoint(bp));
        return "Removed breakpoint #" + index + " (" + desc + ").";
    }
}
