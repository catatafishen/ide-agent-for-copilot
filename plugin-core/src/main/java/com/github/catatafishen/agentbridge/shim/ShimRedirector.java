package com.github.catatafishen.agentbridge.shim;

import com.github.catatafishen.agentbridge.acp.client.intercept.RedirectPlan;
import com.github.catatafishen.agentbridge.acp.client.intercept.ShellRedirectPlanner;
import com.github.catatafishen.agentbridge.acp.client.intercept.VisibleProcessRunner;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between the on-disk command shim and the in-IDE MCP layer.
 *
 * <p>Given an argv list received from the shim's HTTP POST, applies one of
 * three routings:
 * <ol>
 *   <li><b>MCP redirect</b> — {@link ShellRedirectPlanner} returns a plan;
 *       the chosen MCP tool runs against the IDE buffer/index so reads see
 *       unsaved edits and writes keep the IDE in sync.</li>
 *   <li><b>Visible fallthrough</b> — argv[0] is in
 *       {@link ShimManager#VISIBLE_FALLTHROUGH_COMMANDS}; the real binary runs
 *       server-side via {@link VisibleProcessRunner} so the user sees it in a
 *       Run tool window tab. Output is captured and returned to the agent
 *       verbatim, so the agent's protocol contract (stdout + exit code) is
 *       unchanged.</li>
 *   <li><b>Passthrough (null)</b> — neither matches; the shim re-execs the
 *       real binary in-process, invisibly to the user. This is the legacy
 *       behaviour and the safe default for anything we haven't whitelisted.</li>
 * </ol>
 */
public final class ShimRedirector {

    private static final Logger LOG = Logger.getInstance(ShimRedirector.class);

    /**
     * Cap on captured stdout for visible fallthrough — protects the IDE from
     * an OOM if the agent runs e.g. {@code mvn -X} which can dump hundreds of
     * MB. Anything beyond this is truncated with a marker line; the user can
     * still scroll the full output in the Run tool window.
     */
    private static final int VISIBLE_OUTPUT_CAP_BYTES = 4 * 1024 * 1024;

    private final @NotNull Project project;

    public ShimRedirector(@NotNull Project project) {
        this.project = project;
    }

    public @Nullable Result tryRedirect(@NotNull List<String> argv, @Nullable String cwd) {
        if (argv.isEmpty()) return null;

        Result mcp = tryMcpRedirect(argv);
        if (mcp != null) return mcp;

        if (ShimManager.VISIBLE_FALLTHROUGH_COMMANDS.contains(argv.getFirst())) {
            return runVisible(argv, cwd);
        }

        return null;
    }

    private @Nullable Result tryMcpRedirect(@NotNull List<String> argv) {
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

    /**
     * Runs the real binary server-side under a Run tool window tab so the user
     * sees the live output, then returns the captured stdout + exit code to
     * the agent. PATH is stripped of the shim dir to prevent recursion.
     */
    private @Nullable Result runVisible(@NotNull List<String> argv, @Nullable String cwd) {
        ShimManager.EnvSnapshot snap = ShimManager.getInstance(project).snapshot();
        if (snap == null) return null; // shim not installed; nothing to strip — bail to passthrough

        String[] args = argv.subList(1, argv.size()).toArray(new String[0]);
        Map<String, String> env = realPathEnv(snap.shimDir().toString());
        GeneralCommandLine cmd = VisibleProcessRunner.buildCommandLine(argv.getFirst(), args, cwd, env);

        StringBuilder buf = new StringBuilder();
        boolean[] capped = {false};
        VisibleProcessRunner.OutputSink sink = chunk -> {
            synchronized (buf) {
                if (capped[0]) return;
                if (buf.length() + chunk.length() > VISIBLE_OUTPUT_CAP_BYTES) {
                    int remaining = VISIBLE_OUTPUT_CAP_BYTES - buf.length();
                    if (remaining > 0) buf.append(chunk, 0, remaining);
                    buf.append("\n[agentbridge: output truncated at ")
                        .append(VISIBLE_OUTPUT_CAP_BYTES)
                        .append(" bytes — see the Run tool window for the full log]\n");
                    capped[0] = true;
                } else {
                    buf.append(chunk);
                }
            }
        };

        try {
            String tabTitle = "agent: " + String.join(" ", argv);
            Process process = new VisibleProcessRunner(project)
                .start(cmd, tabTitle, sink);
            int exit = process.waitFor();
            String stdout;
            synchronized (buf) {
                stdout = buf.toString();
            }
            LOG.info("Shim visible-fallthrough: " + String.join(" ", argv)
                + " (exit=" + exit + ", " + stdout.length() + " bytes captured)");
            return new Result(stdout, exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Shim visible-fallthrough interrupted for " + String.join(" ", argv), e);
            return null;
        } catch (Exception e) {
            LOG.warn("Shim visible-fallthrough failed for " + String.join(" ", argv)
                + " — falling through to real binary", e);
            return null;
        }
    }

    /**
     * Build an env map that inherits the IDE's environment but strips the
     * shim directory from {@code PATH} so the visible exec hits the real
     * binary instead of recursing.
     *
     * <p>Package-private for tests.
     */
    static @NotNull Map<String, String> realPathEnv(@NotNull String shimDirPath) {
        Map<String, String> env = new HashMap<>(System.getenv());
        String path = env.get("PATH");
        if (path != null) {
            String sep = System.getProperty("path.separator", ":");
            String[] parts = path.split(java.util.regex.Pattern.quote(sep), -1);
            StringBuilder kept = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty() || p.equals(shimDirPath)) continue;
                if (!kept.isEmpty()) kept.append(sep);
                kept.append(p);
            }
            env.put("PATH", kept.toString());
        }
        return env;
    }

    static boolean isMcpError(@Nullable String result) {
        return result != null && result.startsWith("Error");
    }

    /**
     * Captured stdout and exit code of a shim invocation.
     */
    public record Result(@NotNull String stdout, int exitCode) {
    }
}
