package com.github.copilot.intellij.psi;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reflection-based integration with SonarQube for IDE (formerly SonarLint) plugin.
 * All interaction is via reflection since SonarLint has no public API.
 * Gracefully handles the plugin being absent or API changes.
 */
final class SonarQubeIntegration {

    private static final Logger LOG = Logger.getInstance(SonarQubeIntegration.class);
    private static final String SONAR_PLUGIN_ID = "org.sonarlint.idea";
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_WAIT_SECONDS = 120;

    private final Project project;

    SonarQubeIntegration(Project project) {
        this.project = project;
    }

    /**
     * Check if SonarQube for IDE plugin is installed and enabled.
     */
    static boolean isInstalled() {
        return PluginManagerCore.isPluginInstalled(PluginId.getId(SONAR_PLUGIN_ID));
    }

    /**
     * Trigger SonarQube analysis and collect results.
     *
     * @param scope    "all", "changed", or a file path
     * @param limit    max results to return
     * @param offset   pagination offset
     * @return formatted findings string
     */
    String runAnalysis(String scope, int limit, int offset) {
        if (!isInstalled()) {
            return "Error: SonarQube for IDE plugin is not installed.";
        }

        try {
            // 1. Trigger the appropriate SonarLint action
            String actionId = resolveActionId(scope);
            boolean triggered = triggerAction(actionId, scope);
            if (!triggered) {
                return "Error: Could not trigger SonarQube analysis. Action '" + actionId + "' not found or not available.";
            }

            // 2. Wait for analysis to complete
            boolean completed = waitForAnalysisCompletion();
            if (!completed) {
                LOG.warn("SonarQube analysis did not complete within " + MAX_WAIT_SECONDS + "s, collecting partial results");
            }

            // 3. Collect findings from OnTheFlyFindingsHolder
            List<String> findings = collectFindings();

            // 4. Format output
            return formatOutput(findings, limit, offset);
        } catch (Exception e) {
            LOG.warn("SonarQube analysis failed", e);
            return "Error running SonarQube analysis: " + e.getMessage();
        }
    }

    private String resolveActionId(String scope) {
        if ("changed".equalsIgnoreCase(scope)) {
            return "SonarLint.AnalyzeChangedFiles";
        }
        return "SonarLint.AnalyzeAllFiles";
    }

    private boolean triggerAction(String actionId, String scope) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            LOG.warn("SonarLint action not found: " + actionId);
            return false;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .build();
                var presentation = action.getTemplatePresentation().clone();
                var event = AnActionEvent.createEvent(
                    dataContext, presentation, "AgenticCopilot",
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);

                action.update(event);
                if (event.getPresentation().isEnabledAndVisible()) {
                    action.actionPerformed(event);
                    future.complete(true);
                } else {
                    LOG.warn("SonarLint action not enabled: " + actionId);
                    future.complete(false);
                }
            } catch (Exception e) {
                LOG.warn("Failed to trigger SonarLint action: " + actionId, e);
                future.complete(false);
            }
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Timeout waiting for SonarLint action trigger", e);
            return false;
        }
    }

    /**
     * Poll AnalysisStatus.isRunning() via reflection until analysis completes.
     */
    private boolean waitForAnalysisCompletion() {
        try {
            Class<?> statusClass = Class.forName("org.sonarlint.intellij.analysis.AnalysisStatus");
            Object statusService = project.getService(statusClass);
            if (statusService == null) {
                LOG.warn("Could not get AnalysisStatus service");
                return true; // proceed anyway
            }

            Method isRunningMethod = statusClass.getMethod("isRunning");

            long startTime = System.currentTimeMillis();
            long maxWaitMs = MAX_WAIT_SECONDS * 1000L;

            // Wait a bit for analysis to start
            Thread.sleep(1000);

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                Boolean running = (Boolean) isRunningMethod.invoke(statusService);
                if (!running) {
                    // Analysis completed — wait a moment for results to propagate
                    Thread.sleep(500);
                    return true;
                }
                //noinspection BusyWait
                Thread.sleep(POLL_INTERVAL_MS);
            }

            LOG.warn("SonarQube analysis timed out after " + MAX_WAIT_SECONDS + "s");
            return false;
        } catch (ClassNotFoundException e) {
            LOG.info("AnalysisStatus class not found — SonarLint API may have changed");
            // Can't poll, just wait a fixed time
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return true;
        } catch (Exception e) {
            LOG.warn("Error polling SonarQube analysis status", e);
            return true;
        }
    }

    /**
     * Collect findings from SonarLint's OnTheFlyFindingsHolder and AnalysisSubmitter.
     */
    @SuppressWarnings("unchecked")
    private List<String> collectFindings() {
        List<String> results = new ArrayList<>();
        String basePath = project.getBasePath();

        try {
            // Get AnalysisSubmitter service
            Class<?> submitterClass = Class.forName("org.sonarlint.intellij.analysis.AnalysisSubmitter");
            Object submitter = project.getService(submitterClass);
            if (submitter == null) {
                LOG.warn("Could not get AnalysisSubmitter service");
                return results;
            }

            // Access onTheFlyFindingsHolder field
            Field holderField = submitterClass.getDeclaredField("onTheFlyFindingsHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(submitter);
            if (holder == null) {
                LOG.warn("OnTheFlyFindingsHolder is null");
                return results;
            }

            // Get issues: getAllIssues() -> Collection<LiveIssue>
            Method getAllIssues = holder.getClass().getMethod("getAllIssues");
            Collection<?> issues = (Collection<?>) getAllIssues.invoke(holder);
            for (Object issue : issues) {
                String formatted = formatLiveFinding(issue, basePath);
                if (formatted != null) {
                    results.add(formatted);
                }
            }

            // Get hotspots: getAllHotspots() -> Collection<LiveSecurityHotspot>
            try {
                Method getAllHotspots = holder.getClass().getMethod("getAllHotspots");
                Collection<?> hotspots = (Collection<?>) getAllHotspots.invoke(holder);
                for (Object hotspot : hotspots) {
                    String formatted = formatLiveFinding(hotspot, basePath);
                    if (formatted != null) {
                        results.add(formatted);
                    }
                }
            } catch (NoSuchMethodException e) {
                LOG.info("getAllHotspots not available — SonarLint version may differ");
            }

        } catch (ClassNotFoundException e) {
            LOG.info("SonarLint classes not found — plugin may have been updated");
        } catch (Exception e) {
            LOG.warn("Error collecting SonarQube findings", e);
        }

        return results;
    }

    /**
     * Format a LiveFinding (LiveIssue or LiveSecurityHotspot) to a string via reflection.
     * LiveFinding has: getMessage(), getRuleKey(), getValidTextRange(), file()
     * LiveIssue also has: getUserSeverity()
     */
    private String formatLiveFinding(Object finding, String basePath) {
        try {
            // Get message
            Method getMessage = findMethod(finding, "getMessage");
            String message = getMessage != null ? (String) getMessage.invoke(finding) : "unknown";

            // Get rule key
            Method getRuleKey = findMethod(finding, "getRuleKey");
            String ruleKey = getRuleKey != null ? (String) getRuleKey.invoke(finding) : "unknown";

            // Get file path
            String filePath = getFilePath(finding, basePath);

            // Get line number from range
            int line = getLineNumber(finding);

            // Get severity
            String severity = getSeverity(finding);

            return String.format("%s:%d [%s/%s] %s", filePath, line, severity, ruleKey, message);
        } catch (Exception e) {
            LOG.debug("Could not format finding: " + e.getMessage());
            return null;
        }
    }

    private String getFilePath(Object finding, String basePath) {
        try {
            // Try file() method first (newer API)
            Method fileMethod = findMethod(finding, "file");
            if (fileMethod != null) {
                Object vf = fileMethod.invoke(finding);
                if (vf instanceof VirtualFile virtualFile) {
                    String path = virtualFile.getPath();
                    if (basePath != null && path.startsWith(basePath)) {
                        return path.substring(basePath.length() + 1);
                    }
                    return path;
                }
            }

            // Try psiFile() -> getVirtualFile()
            Method psiFileMethod = findMethod(finding, "psiFile");
            if (psiFileMethod != null) {
                Object psiFile = psiFileMethod.invoke(finding);
                if (psiFile != null) {
                    Method getVf = findMethod(psiFile, "getVirtualFile");
                    if (getVf != null) {
                        Object vf = getVf.invoke(psiFile);
                        if (vf instanceof VirtualFile virtualFile) {
                            String path = virtualFile.getPath();
                            if (basePath != null && path.startsWith(basePath)) {
                                return path.substring(basePath.length() + 1);
                            }
                            return path;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get file path from finding");
        }
        return "unknown";
    }

    private int getLineNumber(Object finding) {
        try {
            // Try getValidTextRange() -> getStartOffset(), then convert to line
            Method getRange = findMethod(finding, "getValidTextRange");
            if (getRange != null) {
                Object range = getRange.invoke(finding);
                if (range != null) {
                    Method getStartOffset = findMethod(range, "getStartOffset");
                    if (getStartOffset != null) {
                        int offset = (int) getStartOffset.invoke(range);
                        // Try to get line from the range
                        Method getStartLine = findMethod(finding, "getLine");
                        if (getStartLine != null) {
                            Object lineObj = getStartLine.invoke(finding);
                            if (lineObj instanceof Integer i) return i;
                        }
                        return offset; // fallback to offset
                    }
                }
            }

            // Try getLine() directly
            Method getLine = findMethod(finding, "getLine");
            if (getLine != null) {
                Object lineObj = getLine.invoke(finding);
                if (lineObj instanceof Integer i) return i;
            }
        } catch (Exception e) {
            LOG.debug("Could not get line number from finding");
        }
        return 0;
    }

    private String getSeverity(Object finding) {
        try {
            // Try getHighestImpact() -> name() (newer ImpactSeverity enum)
            Method getImpact = findMethod(finding, "getHighestImpact");
            if (getImpact != null) {
                Object impact = getImpact.invoke(finding);
                if (impact != null) {
                    return impact.toString();
                }
            }

            // Try getUserSeverity() -> name() (legacy IssueSeverity enum)
            Method getSev = findMethod(finding, "getUserSeverity");
            if (getSev != null) {
                Object sev = getSev.invoke(finding);
                if (sev != null) {
                    return sev.toString();
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get severity from finding");
        }
        return "WARNING";
    }

    /**
     * Find a method by name (no-arg) on an object, searching class hierarchy.
     */
    private static Method findMethod(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // try getDeclaredMethod
                try {
                    Method m = clazz.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // continue
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private String formatOutput(List<String> findings, int limit, int offset) {
        if (findings.isEmpty()) {
            return "SonarQube analysis complete. No findings for currently analyzed files.\n" +
                "Note: Findings are available for files that SonarLint has analyzed. " +
                "Open files are analyzed automatically; for full project results, check SonarLint's Report tab.";
        }

        int total = findings.size();
        int end = Math.min(offset + limit, total);
        int start = Math.min(offset, total);

        StringBuilder sb = new StringBuilder();
        sb.append("SonarQube findings (").append(total).append(" total");
        if (start > 0 || end < total) {
            sb.append(", showing ").append(start + 1).append("-").append(end);
        }
        sb.append("):\n\n");

        for (int i = start; i < end; i++) {
            sb.append(findings.get(i)).append('\n');
        }

        if (end < total) {
            sb.append("\nWARNING: ").append(total - end).append(" more findings not shown. Use offset=")
                .append(end).append(" to see more.");
        }

        return sb.toString();
    }
}
