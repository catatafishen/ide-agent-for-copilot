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
import java.util.Map;
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
     * Get SonarLint plugin's classloader for loading its classes via reflection.
     */
    private static ClassLoader getSonarLintClassLoader() {
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId(SONAR_PLUGIN_ID));
        return descriptor != null ? descriptor.getPluginClassLoader() : null;
    }

    /**
     * Load a class from SonarLint's plugin classloader.
     */
    private static Class<?> loadSonarClass(String className) throws ClassNotFoundException {
        ClassLoader cl = getSonarLintClassLoader();
        if (cl == null) throw new ClassNotFoundException("SonarLint classloader not available");
        return Class.forName(className, true, cl);
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
            String basePath = project.getBasePath();

            // 1. Record current analysis result (to detect when new results arrive)
            Object oldResult = getCurrentAnalysisResult();

            // 2. Trigger analysis
            String actionId = resolveActionId(scope);
            boolean triggered = triggerAction(actionId, scope);
            if (!triggered) {
                LOG.warn("Could not trigger analysis action '" + actionId + "'");
                // Return whatever existing results we have
                List<String> fallback = collectFromReportTab(basePath);
                if (fallback.isEmpty()) {
                    fallback = collectFromOnTheFlyHolder(basePath);
                }
                if (!fallback.isEmpty()) {
                    return formatOutput(fallback, limit, offset);
                }
                return "SonarQube analysis could not be triggered. Open the SonarLint Report tab and click 'Analyze All Files' manually, then call this tool again.";
            }

            // 3. Wait for NEW results (different from the ones we had before triggering)
            List<String> findings = waitForNewResults(basePath, oldResult);

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
     * Get the current lastAnalysisResult object from the latest ReportPanel.
     * Used to detect when a new analysis completes (the reference changes).
     */
    private Object getCurrentAnalysisResult() {
        try {
            Class<?> reportTabManagerClass = loadSonarClass("org.sonarlint.intellij.ui.report.ReportTabManager");
            Object reportTabManager = project.getService(reportTabManagerClass);
            if (reportTabManager == null) return null;

            Field reportTabsField = reportTabManagerClass.getDeclaredField("reportTabs");
            reportTabsField.setAccessible(true);
            Map<?, ?> reportTabs = (Map<?, ?>) reportTabsField.get(reportTabManager);
            if (reportTabs == null || reportTabs.isEmpty()) return null;

            Object latestPanel = null;
            for (Object panel : reportTabs.values()) {
                latestPanel = panel;
            }
            if (latestPanel == null) return null;

            Field resultField = latestPanel.getClass().getDeclaredField("lastAnalysisResult");
            resultField.setAccessible(true);
            return resultField.get(latestPanel);
        } catch (Exception e) {
            LOG.debug("Could not read current analysis result");
            return null;
        }
    }

    /**
     * Wait for new analysis results to appear by polling until the lastAnalysisResult
     * object reference changes from oldResult (meaning a new analysis completed).
     * Falls back to stabilization check if no old result existed.
     */
    private List<String> waitForNewResults(String basePath, Object oldResult) {
        long startTime = System.currentTimeMillis();
        long maxWaitMs = MAX_WAIT_SECONDS * 1000L;

        // Initial delay for analysis to start
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        int lastCount = -1;
        int stablePolls = 0;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            // Check if a NEW analysis result has appeared
            Object currentResult = getCurrentAnalysisResult();
            if (currentResult != null && currentResult != oldResult) {
                // New result arrived — wait briefly for it to fully populate, then collect
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                List<String> results = collectFromReportTab(basePath);
                if (!results.isEmpty()) {
                    // Double-check: wait and re-poll to ensure results are stable
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    List<String> moreResults = collectFromReportTab(basePath);
                    return moreResults.size() >= results.size() ? moreResults : results;
                }
            }

            // Fallback stabilization: if we had no old result, check if results appear and stabilize
            if (oldResult == null) {
                List<String> results = collectFromReportTab(basePath);
                int count = results.size();
                if (count > 0 && count == lastCount) {
                    stablePolls++;
                    if (stablePolls >= 3) {
                        return results;
                    }
                } else {
                    stablePolls = 0;
                }
                lastCount = count;
            }

            //noinspection BusyWait
            try { Thread.sleep(POLL_INTERVAL_MS * 4); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Timeout: return whatever we can find
        LOG.warn("SonarQube analysis timed out after " + MAX_WAIT_SECONDS + "s");
        List<String> results = collectFromReportTab(basePath);
        if (results.isEmpty()) {
            results = collectFromOnTheFlyHolder(basePath);
        }
        if (results.isEmpty()) {
            return List.of("Analysis is still running. Call this tool again to check for results.");
        }
        return results;
    }

    /**
     * Collect findings from ReportTabManager → ReportPanel.lastAnalysisResult → LiveFindings.
     * This is where analyzeAllFiles() stores its results.
     */
    private List<String> collectFromReportTab(String basePath) {
        List<String> results = new ArrayList<>();
        try {
            Class<?> reportTabManagerClass = loadSonarClass("org.sonarlint.intellij.ui.report.ReportTabManager");
            Object reportTabManager = project.getService(reportTabManagerClass);
            if (reportTabManager == null) {
                LOG.info("ReportTabManager service not available");
                return results;
            }

            // Access reportTabs: ConcurrentHashMap<String, ReportPanel>
            Field reportTabsField = reportTabManagerClass.getDeclaredField("reportTabs");
            reportTabsField.setAccessible(true);
            Map<?, ?> reportTabs = (Map<?, ?>) reportTabsField.get(reportTabManager);
            if (reportTabs == null || reportTabs.isEmpty()) {
                LOG.info("No report tabs found");
                return results;
            }

            // Get the most recent report panel (iterate to last value)
            Object latestPanel = null;
            for (Object panel : reportTabs.values()) {
                latestPanel = panel;
            }
            if (latestPanel == null) return results;

            // Access lastAnalysisResult field on ReportPanel
            Field resultField = latestPanel.getClass().getDeclaredField("lastAnalysisResult");
            resultField.setAccessible(true);
            Object analysisResult = resultField.get(latestPanel);
            if (analysisResult == null) {
                LOG.info("ReportPanel has no analysis result yet");
                return results;
            }

            // Get findings: AnalysisResult.findings -> LiveFindings
            Method getFindingsMethod = analysisResult.getClass().getMethod("getFindings");
            Object liveFindings = getFindingsMethod.invoke(analysisResult);
            if (liveFindings == null) return results;

            // Get issuesPerFile: Map<VirtualFile, Collection<LiveIssue>>
            Method getIssuesMethod = liveFindings.getClass().getMethod("getIssuesPerFile");
            Map<?, ?> issuesPerFile = (Map<?, ?>) getIssuesMethod.invoke(liveFindings);
            if (issuesPerFile != null) {
                for (Map.Entry<?, ?> entry : issuesPerFile.entrySet()) {
                    Collection<?> issues = (Collection<?>) entry.getValue();
                    for (Object issue : issues) {
                        String formatted = formatLiveFinding(issue, basePath);
                        if (formatted != null) results.add(formatted);
                    }
                }
            }

            // Get securityHotspotsPerFile: Map<VirtualFile, Collection<LiveSecurityHotspot>>
            try {
                Method getHotspotsMethod = liveFindings.getClass().getMethod("getSecurityHotspotsPerFile");
                Map<?, ?> hotspotsPerFile = (Map<?, ?>) getHotspotsMethod.invoke(liveFindings);
                if (hotspotsPerFile != null) {
                    for (Map.Entry<?, ?> entry : hotspotsPerFile.entrySet()) {
                        Collection<?> hotspots = (Collection<?>) entry.getValue();
                        for (Object hotspot : hotspots) {
                            String formatted = formatLiveFinding(hotspot, basePath);
                            if (formatted != null) results.add(formatted);
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                LOG.info("getSecurityHotspotsPerFile not available");
            }

            LOG.info("Collected " + results.size() + " SonarQube findings from Report tab");
        } catch (ClassNotFoundException e) {
            LOG.info("ReportTabManager class not found — SonarLint API may have changed");
        } catch (Exception e) {
            LOG.warn("Error collecting from ReportTab", e);
        }
        return results;
    }

    /**
     * Fallback: collect from OnTheFlyFindingsHolder (only open file findings).
     */
    private List<String> collectFromOnTheFlyHolder(String basePath) {
        List<String> results = new ArrayList<>();
        try {
            Class<?> submitterClass = loadSonarClass("org.sonarlint.intellij.analysis.AnalysisSubmitter");
            Object submitter = project.getService(submitterClass);
            if (submitter == null) return results;

            Field holderField = submitterClass.getDeclaredField("onTheFlyFindingsHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(submitter);
            if (holder == null) return results;

            Method getAllIssues = holder.getClass().getMethod("getAllIssues");
            Collection<?> issues = (Collection<?>) getAllIssues.invoke(holder);
            for (Object issue : issues) {
                String formatted = formatLiveFinding(issue, basePath);
                if (formatted != null) results.add(formatted);
            }

            try {
                Method getAllHotspots = holder.getClass().getMethod("getAllHotspots");
                Collection<?> hotspots = (Collection<?>) getAllHotspots.invoke(holder);
                for (Object hotspot : hotspots) {
                    String formatted = formatLiveFinding(hotspot, basePath);
                    if (formatted != null) results.add(formatted);
                }
            } catch (NoSuchMethodException e) {
                LOG.info("getAllHotspots not available");
            }

            if (!results.isEmpty()) {
                LOG.info("Collected " + results.size() + " findings from OnTheFlyFindingsHolder");
            }
        } catch (ClassNotFoundException e) {
            LOG.info("SonarLint classes not found");
        } catch (Exception e) {
            LOG.warn("Error collecting from OnTheFlyFindingsHolder", e);
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
            // Use getRange() -> RangeMarker -> Document.getLineNumber(offset)
            // RangeMarker stores character offsets; Document converts to line numbers
            Method getRangeMethod = findMethod(finding, "getRange");
            if (getRangeMethod != null) {
                Object rangeObj = getRangeMethod.invoke(finding);
                if (rangeObj instanceof com.intellij.openapi.editor.RangeMarker rm && rm.isValid()) {
                    return rm.getDocument().getLineNumber(rm.getStartOffset()) + 1; // 0-based → 1-based
                }
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
