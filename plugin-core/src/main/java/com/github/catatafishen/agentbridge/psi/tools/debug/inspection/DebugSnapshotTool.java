package com.github.catatafishen.agentbridge.psi.tools.debug.inspection;

import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import org.jetbrains.annotations.NotNull;

public final class DebugSnapshotTool extends DebugTool {

    private static final String PARAM_INCLUDE_SOURCE = "include_source";
    private static final String PARAM_INCLUDE_VARIABLES = "include_variables";
    private static final String PARAM_INCLUDE_STACK = "include_stack";


    public DebugSnapshotTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_snapshot";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Snapshot";
    }

    @Override
    public @NotNull String description() {
        return "Get the current debugger state: source context, local variables, and call stack. Requires a paused session.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_INCLUDE_SOURCE, TYPE_BOOLEAN, "Include source code context around current line (default: true)"),
            Param.optional(PARAM_INCLUDE_VARIABLES, TYPE_BOOLEAN, "Include local variables in current frame (default: true)"),
            Param.optional(PARAM_INCLUDE_STACK, TYPE_BOOLEAN, "Include the call stack (default: true)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        boolean includeSource = !args.has(PARAM_INCLUDE_SOURCE) || args.get(PARAM_INCLUDE_SOURCE).getAsBoolean();
        boolean includeVars = !args.has(PARAM_INCLUDE_VARIABLES) || args.get(PARAM_INCLUDE_VARIABLES).getAsBoolean();
        boolean includeStack = !args.has(PARAM_INCLUDE_STACK) || args.get(PARAM_INCLUDE_STACK).getAsBoolean();
        return buildSnapshot(session, includeSource, includeVars, includeStack);
    }
}
