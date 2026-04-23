package com.github.catatafishen.agentbridge.shim;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns the on-disk command shim directory and the per-process auth token used
 * by the shim ↔ {@code /shim-exec} round-trip.
 *
 * <p>On first call to {@link #install()}, copies the bundled
 * {@code agentbridge-shim.sh} resource into a per-project directory under
 * IntelliJ's system path, once per command name we want to intercept. The
 * directory is then returned and prepended to {@code PATH} for every ACP agent
 * subprocess (Copilot, Junie, OpenCode, Kiro).
 *
 * <p>Each installed file is an independent copy of the same script — using
 * {@link Files#createSymbolicLink} fails on Windows without elevated permissions
 * and a one-shot ~3KB copy is negligible. The script reads {@code $0} basename
 * to decide which command name was invoked.
 *
 * <p>The token defends the {@code /shim-exec} endpoint against unrelated local
 * processes that stumble upon the bound port: only a process spawned by us
 * inherits {@code AGENTBRIDGE_SHIM_TOKEN} and can authenticate.
 */
@Service(Service.Level.PROJECT)
public final class ShimManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(ShimManager.class);

    /**
     * Names that get a copy of the shim. Phase A: tools whose redirects exist
     * in {@code ShellRedirectPlanner}. Extend in Phase D.
     */
    public static final List<String> SHIMMED_COMMANDS =
        List.of("cat", "head", "grep", "egrep", "fgrep", "rg", "git");

    private static final String SHIM_RESOURCE = "/agentbridge/shim/agentbridge-shim.sh";

    private final @NotNull Project project;
    private final @NotNull String token = UUID.randomUUID().toString().replace("-", "");

    private volatile Path shimDir;

    public ShimManager(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ShimManager getInstance(@NotNull Project project) {
        return project.getService(ShimManager.class);
    }

    /**
     * Lazily extract the shim script under one path per shimmed command name and
     * return the directory. Subsequent calls return the cached path.
     *
     * <p>Returns {@code null} when extraction fails — the caller (env injection
     * site in {@code AcpClient}) treats this as "skip the shim" so launches
     * never break because of a shim install failure.
     */
    public synchronized Path install() {
        if (shimDir != null) return shimDir;
        try {
            Path dir = computeShimDir();
            Files.createDirectories(dir);
            byte[] script = readShimResource();

            for (String name : SHIMMED_COMMANDS) {
                Path target = dir.resolve(name);
                Files.write(target, script);
                makeExecutable(target);
            }

            shimDir = dir;
            LOG.info("Installed " + SHIMMED_COMMANDS.size() + " command shims under " + dir);
            return dir;
        } catch (IOException e) {
            LOG.warn("Failed to install command shims for project " + project.getName()
                + " — agent shells will run unintercepted", e);
            return null;
        }
    }

    public @NotNull String getToken() {
        return token;
    }

    private Path computeShimDir() {
        // Per-project subdir keeps multiple open projects from clobbering each
        // other's shim files (tokens differ between projects).
        String projectKey = projectFingerprint();
        return Path.of(PathManager.getSystemPath(), "agentbridge", "shims", projectKey);
    }

    private String projectFingerprint() {
        String basePath = project.getBasePath();
        if (basePath == null) basePath = project.getName();
        return Integer.toHexString(basePath.hashCode());
    }

    private byte[] readShimResource() throws IOException {
        try (InputStream in = ShimManager.class.getResourceAsStream(SHIM_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled shim script not found at " + SHIM_RESOURCE);
            }
            return in.readAllBytes();
        }
    }

    private static void makeExecutable(@NotNull Path path) throws IOException {
        // Windows: PosixFileAttributeView is unsupported; the shim script doesn't
        // run there in Phase A anyway — Phase C will ship a real .exe instead.
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignore) {
            // Non-POSIX FS (Windows). Will be addressed by the Windows shim in Phase C.
        }
    }

    @Override
    public void dispose() {
        Path dir = shimDir;
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            // Delete bottom-up so the directory itself can be removed.
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best-effort */ }
            });
        } catch (IOException ignore) {
            // best-effort cleanup
        }
    }

    /**
     * Returned by {@link #snapshot()} — convenience for the launch-time env injection
     * to avoid two getters.
     */
    public record EnvSnapshot(@NotNull Path shimDir, @NotNull String token, @NotNull Set<String> commands) {
    }

    public EnvSnapshot snapshot() {
        Path dir = install();
        if (dir == null) return null;
        return new EnvSnapshot(dir, token, Set.copyOf(SHIMMED_COMMANDS));
    }
}
