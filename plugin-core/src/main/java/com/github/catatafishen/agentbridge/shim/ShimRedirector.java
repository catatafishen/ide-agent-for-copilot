package com.github.catatafishen.agentbridge.shim;

import com.github.catatafishen.agentbridge.acp.client.intercept.RedirectPlan;
import com.github.catatafishen.agentbridge.acp.client.intercept.ShellRedirectPlanner;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bridge between the on-disk command shim and the in-IDE MCP layer.
 *
 * <p>Given an argv list received from the shim's HTTP POST, runs the
 * {@link ShellRedirectPlanner} and, when a plan is returned, invokes the chosen
 * MCP tool via {@link PsiBridgeService}. Returns either:
 * <ul>
 *   <li>a {@link Result} carrying stdout and a POSIX-ish exit code, or</li>
 *   <li>{@code null} when the command should fall through to the real binary
 *       (no plan, or the MCP call signaled an error — the shim then re-execs
 *       the original tool so the agent still gets a working command).</li>
 * </ul>
 *
 * <p>The redirection logic is identical to the (now-legacy) ACP
 * {@code terminal/create} interceptor — the planner and MCP tools are reused
 * as-is. The only new piece is the HTTP edge that exposes this to a child
 * process running inside the agent's PTY.
 */
public final class ShimRedirector {

    private static final Logger LOG = Logger.getInstance(ShimRedirector.class);

    private final @NotNull Project project;

    public ShimRedirector(@NotNull Project project) {
        this.project = project;
    }

    public @Nullable Result tryRedirect(@NotNull List<String> argv) {
        if (argv.isEmpty()) return null;

        RedirectPlan plan = ShellRedirectPlanner.plan(argv);
        if (plan == null) return null;

        String raw;
        try {
            String result = PsiBridgeService.getInstance(project).callTool(plan.toolName(), plan.args());
            raw = result != null ? result : "";
        } catch (Exception e) {
            LOG.warn("Shim MCP call '" + plan.toolName() + "' failed for argv "
                + String.join(" ", argv) + " — falling through to real binary", e);
            return null;
        }

        if (isMcpError(raw)) {
            LOG.warn("Shim MCP call '" + plan.toolName() + "' returned error for argv "
                + String.join(" ", argv) + " — falling through to real binary: " + raw);
            return null;
        }

        String processed = plan.postProcess().apply(raw);
        int exitCode = plan.exitCodeFor().applyAsInt(processed);

        LOG.info("Shim redirected: " + String.join(" ", argv) + " -> " + plan.toolName()
            + " (exit=" + exitCode + ", " + processed.length() + " bytes)");

        return new Result(processed, exitCode);
    }

    static boolean isMcpError(@Nullable String result) {
        return result != null && result.startsWith("Error");
    }

    /**
     * Captured stdout and exit code of an MCP-redirected shim invocation.
     */
    public record Result(@NotNull String stdout, int exitCode) {
    }
}
