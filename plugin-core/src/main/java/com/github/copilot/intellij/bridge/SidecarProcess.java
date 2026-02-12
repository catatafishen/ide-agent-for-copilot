package com.github.copilot.intellij.bridge;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle of the Go sidecar process.
 * Starts the binary, monitors its health, and handles graceful shutdown.
 */
public class SidecarProcess {
    private static final Logger LOG = Logger.getInstance(SidecarProcess.class);
    private static final Pattern PORT_PATTERN = Pattern.compile("SIDECAR_PORT=(\\d+)");
    private static final int STARTUP_TIMEOUT_SECONDS = 10;

    private Process process;
    private int port = -1;

    /**
     * Start the sidecar process and wait for it to report its port.
     */
    public void start() throws SidecarException {
        try {
            Path binaryPath = getSidecarBinaryPath();
            LOG.info("Starting sidecar from: " + binaryPath);

            GeneralCommandLine commandLine = new GeneralCommandLine()
                    .withExePath(binaryPath.toString())
                    .withParameters("--port", "0", "--debug")
                    .withRedirectErrorStream(false);

            // Ensure gh CLI is on PATH for the sidecar (SDK needs it for auth)
            ensureGhOnPath(commandLine);

            process = commandLine.createProcess();

            // Read stdout to get the port
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            boolean portFound = false;
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < STARTUP_TIMEOUT_SECONDS * 1000) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                LOG.debug("Sidecar output: " + line);

                Matcher matcher = PORT_PATTERN.matcher(line);
                if (matcher.find()) {
                    port = Integer.parseInt(matcher.group(1));
                    portFound = true;
                    LOG.info("Sidecar listening on port: " + port);
                    break;
                }
            }

            if (!portFound) {
                throw new SidecarException("Failed to detect sidecar port within timeout");
            }

            // Verify process is still alive
            if (!process.isAlive()) {
                throw new SidecarException("Sidecar process died immediately after start");
            }

            // Wait for HTTP server to be ready (poll /health endpoint)
            waitForServerReady();

        } catch (IOException | ExecutionException e) {
            throw new SidecarException("Failed to start sidecar process", e);
        }
    }

    /**
     * Get the port the sidecar is listening on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Check if the sidecar process is running.
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Stop the sidecar process gracefully.
     */
    public void stop() {
        if (process == null) {
            return;
        }

        LOG.info("Stopping sidecar process...");

        // Try graceful shutdown first
        process.destroy();

        try {
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                LOG.warn("Sidecar did not stop gracefully, forcing...");
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            LOG.error("Interrupted while stopping sidecar", e);
            Thread.currentThread().interrupt();
        }

        process = null;
        port = -1;
    }

    /**
     * Poll the sidecar's /health endpoint until it responds or timeout.
     */
    private void waitForServerReady() throws SidecarException {
        int maxAttempts = 20;
        int delayMs = 250;
        
        for (int i = 0; i < maxAttempts; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) 
                    new URL("http://localhost:" + port + "/health").openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    LOG.info("Sidecar HTTP server ready after " + (i + 1) + " attempts");
                    return;
                }
            } catch (IOException ignored) {
                // Server not ready yet
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SidecarException("Interrupted while waiting for sidecar");
            }
        }
        LOG.warn("Sidecar health check did not respond within " + (maxAttempts * delayMs) + "ms, proceeding anyway");
    }

    /**
     * Get the path to the sidecar binary.
     * Looks in the plugin's installation directory.
     */
    @NotNull
    private Path getSidecarBinaryPath() throws SidecarException {
        String binaryName = SystemInfo.isWindows ? "copilot-sidecar.exe" : "copilot-sidecar";
        
        // Try 1: Relative path from current working directory (development - when running from project root)
        Path devPath = Paths.get("copilot-bridge", "bin", binaryName);
        if (devPath.toFile().exists()) {
            LOG.info("Found sidecar binary (dev mode): " + devPath.toAbsolutePath());
            return devPath.toAbsolutePath();
        }

        // Try 2: Absolute path from user's project directory (development - when running from IDE)
        String userHome = System.getProperty("user.home");
        Path projectPath = Paths.get(userHome, "IdeaProjects", "intellij-copilot-plugin", "copilot-bridge", "bin", binaryName);
        if (projectPath.toFile().exists()) {
            LOG.info("Found sidecar binary (project path): " + projectPath);
            return projectPath;
        }

        // Try 3: Plugin resources directory (bundled with plugin JAR)
        try {
            URL resourceUrl = getClass().getClassLoader().getResource("bin/" + binaryName);
            if (resourceUrl != null) {
                // Extract from JAR to temp directory if needed
                Path tempBinary = extractBinaryFromResources(resourceUrl, binaryName);
                LOG.info("Found sidecar binary (resources): " + tempBinary);
                return tempBinary;
            }
        } catch (Exception e) {
            LOG.warn("Could not load binary from resources", e);
        }

        LOG.error("Sidecar binary not found in any location. Tried:");
        LOG.error("  - Development: " + devPath.toAbsolutePath());
        LOG.error("  - Project: " + projectPath);
        LOG.error("  - Resources: bin/" + binaryName);
        throw new SidecarException("Sidecar binary not found: " + binaryName);
    }

    /**
     * Extract binary from JAR resources to a temporary directory.
     */
    private Path extractBinaryFromResources(URL resourceUrl, String binaryName) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "copilot-sidecar");
        tempDir.toFile().mkdirs();
        
        Path tempBinary = tempDir.resolve(binaryName);
        
        // Copy from resources to temp file
        try (java.io.InputStream in = resourceUrl.openStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(tempBinary.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        
        // Make executable (Unix/Mac)
        if (!SystemInfo.isWindows) {
            tempBinary.toFile().setExecutable(true);
        }
        
        return tempBinary;
    }

    /**
     * Ensure gh CLI is on PATH for the sidecar process.
     * The Copilot SDK needs gh for authentication.
     */
    private void ensureGhOnPath(GeneralCommandLine commandLine) {
        String[] knownGhDirs = {
            "C:\\Program Files\\GitHub CLI",
            "C:\\Program Files (x86)\\GitHub CLI",
            "C:\\Tools\\gh\\bin",
            System.getProperty("user.home") + "\\AppData\\Local\\GitHub CLI"
        };

        String currentPath = commandLine.getEnvironment().getOrDefault("PATH",
                System.getenv("PATH"));

        StringBuilder extraPaths = new StringBuilder();
        for (String dir : knownGhDirs) {
            if (new java.io.File(dir, SystemInfo.isWindows ? "gh.exe" : "gh").exists()) {
                if (!currentPath.contains(dir)) {
                    extraPaths.append(";").append(dir);
                    LOG.info("Adding gh CLI to sidecar PATH: " + dir);
                }
            }
        }

        if (extraPaths.length() > 0) {
            commandLine.getEnvironment().put("PATH", currentPath + extraPaths);
        }
    }
}
