package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.session;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.NotNull;

public final class DebugSessionListTool extends DebugTool {

    public DebugSessionListTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_session_list";
    }

    @Override
    public @NotNull String displayName() {
        return "List Debug Sessions";
    }

    @Override
    public @NotNull String description() {
        return "List all active debug sessions with their current position and status";
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
        XDebugSession[] sessions = XDebuggerManager.getInstance(project).getDebugSessions();
        if (sessions.length == 0) return "No active debug sessions.";

        XDebugSession current = XDebuggerManager.getInstance(project).getCurrentSession();
        var sb = new StringBuilder("Active debug sessions (").append(sessions.length).append("):\n\n");
        for (int i = 0; i < sessions.length; i++) {
            XDebugSession s = sessions[i];
            sb.append('#').append(i + 1).append(": ").append(s.getSessionName());
            if (s == current) sb.append(" [active]");

            if (s.isStopped()) {
                sb.append(" — STOPPED");
            } else if (s.getSuspendContext() != null) {
                XSourcePosition pos = s.getCurrentPosition();
                if (pos != null) {
                    sb.append(" — PAUSED at ").append(pos.getFile().getName()).append(':').append(pos.getLine() + 1);
                } else {
                    sb.append(" — PAUSED");
                }
            } else {
                sb.append(" — RUNNING");
            }
            sb.append('\n');
        }
        sb.append("\nUse 'run_configuration' to start a new debug session.");
        return sb.toString().strip();
    }
}
