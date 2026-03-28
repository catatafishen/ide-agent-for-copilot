package com.github.catatafishen.ideagentforcopilot.psi.tools.debug.inspection;

import com.github.catatafishen.ideagentforcopilot.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.frame.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class DebugVariableDetailTool extends DebugTool {

    public DebugVariableDetailTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_variable_detail";
    }

    @Override
    public @NotNull String displayName() {
        return "Debug Variable Detail";
    }

    @Override
    public @NotNull String description() {
        return "Expand a variable or object to see its children/fields to a given depth. Use 'path' like 'myObj' or 'myObj.field.nested'.";
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
                {"path", TYPE_STRING, "Variable path to expand (e.g., 'myVar' or 'myObj.field'). Top-level variable name must match exactly."},
                {"depth", TYPE_INTEGER, "Maximum expansion depth (default: 2, max: 5)"},
        }, "path");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requirePausedSession();
        String path = args.get("path").getAsString();
        int depth = args.has("depth") ? Math.min(args.get("depth").getAsInt(), 5) : 2;

        XSuspendContext ctx = session.getSuspendContext();
        if (ctx == null) return "Session has no suspend context.";
        XExecutionStack stack = ctx.getActiveExecutionStack();
        if (stack == null) return "No active execution stack.";

        XStackFrame frame = getTopFrame(stack);
        if (frame == null) return "Could not get current stack frame.";

        String[] parts = path.split("\\.", -1);
        XValue target = findValueInFrame(frame, parts, 0);
        if (target == null) {
            return "Variable '" + parts[0] + "' not found in current frame. Run debug_snapshot to see available variables.";
        }

        var sb = new StringBuilder(path).append(" =\n");
        expandValue(sb, target, depth, 0, "  ");
        return sb.toString().strip();
    }

    @Nullable
    private XValue findValueInFrame(@NotNull XStackFrame frame, String[] parts, int partIndex) {
        CompletableFuture<XValueChildrenList> childFuture = new CompletableFuture<>();
        frame.computeChildren(childrenNode(childFuture));
        try {
            XValueChildrenList children = childFuture.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                if (parts[partIndex].equals(children.getName(i))) {
                    XValue found = children.getValue(i);
                    if (partIndex == parts.length - 1) return found;
                    return findChildValue(found, parts, partIndex + 1);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    @Nullable
    private XValue findChildValue(@NotNull XValue parent, String[] parts, int partIndex) {
        if (partIndex >= parts.length) return parent;
        CompletableFuture<XValueChildrenList> future = new CompletableFuture<>();
        parent.computeChildren(childrenNode(future));
        try {
            XValueChildrenList children = future.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                if (parts[partIndex].equals(children.getName(i))) {
                    XValue found = children.getValue(i);
                    if (partIndex == parts.length - 1) return found;
                    return findChildValue(found, parts, partIndex + 1);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private void expandValue(@NotNull StringBuilder sb, @NotNull XValue value, int maxDepth,
                             int currentDepth, @NotNull String indent) {
        String rendered = renderValue(value);
        sb.append(indent).append(rendered).append('\n');

        if (currentDepth >= maxDepth) return;

        CompletableFuture<XValueChildrenList> childFuture = new CompletableFuture<>();
        value.computeChildren(childrenNode(childFuture));

        try {
            XValueChildrenList children = childFuture.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            for (int i = 0; i < children.size(); i++) {
                sb.append(indent).append("  ").append(children.getName(i)).append(" =\n");
                expandValue(sb, children.getValue(i), maxDepth, currentDepth + 1, indent + "    ");
            }
        } catch (Exception ignored) {
            // skip children on error
        }
    }
}
