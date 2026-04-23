package com.github.catatafishen.agentbridge.shim;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the bash shim ↔ HTTP contract.
 *
 * <p>Spins up a tiny in-process HTTP server that mimics the
 * {@code /shim-exec} responses, copies the bundled shim resource onto disk under
 * a renamed file (so {@code $0} basename matches a real command name), and runs
 * it via {@code bash}. Verifies that:
 *
 * <ul>
 *   <li>HTTP 200 + {@code "EXIT N\n<stdout>"} body causes the shim to print the
 *       stdout and exit with the given code.</li>
 *   <li>HTTP 204 causes the shim to fall through to the real binary on PATH
 *       (in this test we install a fake "{@code cat}" stub on PATH so we can
 *       observe fall-through without interfering with the system {@code cat}).</li>
 *   <li>Missing {@code AGENTBRIDGE_SHIM_PORT} skips the HTTP round-trip
 *       entirely.</li>
 * </ul>
 *
 * <p>Disabled on Windows — the bash shim is for Linux/macOS only. Phase C
 * adds a real {@code .exe} for Windows.
 */
@DisabledOnOs(OS.WINDOWS)
class ShimScriptE2eTest {

    private HttpServer server;
    private int port;
    private Path workDir;
    private Path shimDir;
    private Path fakeBinDir;
    private final ConcurrentLinkedQueue<List<String>> recordedArgvs = new ConcurrentLinkedQueue<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "EXIT 0\nhello from MCP";

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createTempDirectory("shim-e2e");
        shimDir = workDir.resolve("shims");
        fakeBinDir = workDir.resolve("fakebin");
        Files.createDirectories(shimDir);
        Files.createDirectories(fakeBinDir);

        // Copy the production shim under a renamed file so $0 basename = "cat"
        copyShimAsCat();

        // Tiny fake "cat" on a separate dir we put after the shim dir on PATH —
        // exercised only when the shim falls through.
        Path realCat = fakeBinDir.resolve("cat");
        Files.writeString(realCat, "#!/usr/bin/env bash\necho fallthrough-cat-saw \"$@\"\n");
        markExecutable(realCat);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/shim-exec", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                recordedArgvs.add(ShimController.parseArgv(new String(body, StandardCharsets.UTF_8)));
                if (responseStatus == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(responseStatus, resp.length);
                    exchange.getResponseBody().write(resp);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop(0);
        deleteRecursively(workDir);
    }

    @Test
    void redirectedResponsePrintsStdoutAndExitsWithCode() throws Exception {
        responseStatus = 200;
        responseBody = "EXIT 7\nrouted-output";

        ProcessResult r = runShim(List.of("foo.txt"), true, "tok");

        assertEquals(7, r.exitCode, "shim should exit with code from EXIT line");
        assertEquals("routed-output", r.stdout, "shim should print body after EXIT line");
        assertEquals(1, recordedArgvs.size(), "shim should POST exactly once");
        assertEquals(List.of("cat", "foo.txt"), recordedArgvs.peek(),
            "argv must include $0 basename then user args");
    }

    @Test
    void noPortFallsThroughWithoutHttp() throws Exception {
        ProcessResult r = runShim(List.of("hello.txt"), false, null);

        assertEquals(0, r.exitCode);
        assertTrue(r.stdout.contains("fallthrough-cat-saw hello.txt"),
            "shim should exec the fake cat when AGENTBRIDGE_SHIM_PORT is unset; stdout=" + r.stdout);
        assertTrue(recordedArgvs.isEmpty(), "no HTTP call expected when port is unset");
    }

    @Test
    void serverReturns204CausesFallThrough() throws Exception {
        responseStatus = 204;
        responseBody = "";

        ProcessResult r = runShim(List.of("payload.txt"), true, "tok");

        assertEquals(0, r.exitCode);
        assertTrue(r.stdout.contains("fallthrough-cat-saw payload.txt"),
            "204 response must cause fall-through; stdout=" + r.stdout);
        assertEquals(1, recordedArgvs.size(), "HTTP call still happens, then fall-through");
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private ProcessResult runShim(List<String> args, boolean withPort, String token) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(shimDir.resolve("cat").toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // PATH order matters: shim dir first, then the fake-bin dir. The shim
        // itself strips its own dir from PATH before exec'ing, so fall-through
        // hits the fake cat in fakeBinDir.
        String pathPrefix = shimDir + ":" + fakeBinDir + ":";
        String currentPath = System.getenv("PATH");
        pb.environment().put("PATH", pathPrefix + (currentPath == null ? "" : currentPath));
        if (withPort) pb.environment().put("AGENTBRIDGE_SHIM_PORT", Integer.toString(port));
        if (token != null) pb.environment().put("AGENTBRIDGE_SHIM_TOKEN", token);
        pb.redirectErrorStream(false);

        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("shim timed out; stdout=" + out + " stderr=" + err);
        }
        return new ProcessResult(p.exitValue(), out, err);
    }

    private void copyShimAsCat() throws IOException {
        try (InputStream in = ShimManager.class.getResourceAsStream("/agentbridge/shim/agentbridge-shim.sh")) {
            assertNotNull(in, "bundled shim resource must be on classpath");
            Path target = shimDir.resolve("cat");
            Files.write(target, in.readAllBytes());
            markExecutable(target);
        }
    }

    private static void markExecutable(Path p) throws IOException {
        Files.setPosixFilePermissions(p, EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        ));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort tempdir cleanup; OS will reap
                }
            });
        }
    }
}
