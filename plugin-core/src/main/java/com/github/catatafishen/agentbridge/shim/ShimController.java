package com.github.catatafishen.agentbridge.shim;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP handler exposed by {@code McpHttpServer} at {@code POST /shim-exec}.
 *
 * <p>Receives form-encoded {@code argv=…} fields from the on-disk command shim,
 * checks the per-process auth token, and either returns a redirected response
 * or instructs the shim to fall through to the real binary.
 *
 * <p>Wire protocol (kept deliberately tiny so the shim can parse it with bash
 * builtins only):
 *
 * <pre>
 * Request:  POST /shim-exec
 *           Header:  X-Shim-Token: &lt;random-per-process-token&gt;
 *           Body:    application/x-www-form-urlencoded, repeated `argv=…` fields
 *
 * Response: 200 — redirected. Body is "EXIT &lt;code&gt;\n&lt;stdout-bytes&gt;".
 *           204 — passthrough. Empty body. Shim execs real binary.
 *           401 — bad token. Shim execs real binary.
 *           400 — malformed request. Shim execs real binary.
 * </pre>
 */
public final class ShimController implements HttpHandler {

    private static final Logger LOG = Logger.getInstance(ShimController.class);
    private static final int MAX_BODY_BYTES = 1 * 1024 * 1024; // 1 MB of argv is plenty

    private final @NotNull Project project;
    private final @NotNull String token;

    public ShimController(@NotNull Project project, @NotNull String token) {
        this.project = project;
        this.token = token;
    }

    @Override
    public void handle(@NotNull HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String presented = exchange.getRequestHeaders().getFirst("X-Shim-Token");
            if (presented == null || !constantTimeEquals(presented, token)) {
                LOG.warn("shim-exec rejected: bad or missing token");
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            List<String> argv = parseArgv(new String(body, StandardCharsets.UTF_8));
            if (argv.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            ShimRedirector.Result result = new ShimRedirector(project).tryRedirect(argv);
            if (result == null) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            byte[] stdout = result.stdout().getBytes(StandardCharsets.UTF_8);
            byte[] header = ("EXIT " + result.exitCode() + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, (long) header.length + stdout.length);
            try (var os = exchange.getResponseBody()) {
                os.write(header);
                os.write(stdout);
            }
        } catch (Exception e) {
            LOG.warn("shim-exec handler error", e);
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignore) {
                // exchange already closed
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Parse {@code argv=v1&argv=v2&...} into an ordered list. Other field names are
     * ignored to keep the protocol forward-compatible.
     */
    static @NotNull List<String> parseArgv(@NotNull String body) {
        List<String> out = new ArrayList<>();
        if (body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq);
            if (!"argv".equals(name)) continue;
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.add(value);
        }
        return out;
    }

    private static boolean constantTimeEquals(@NotNull String a, @NotNull String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
