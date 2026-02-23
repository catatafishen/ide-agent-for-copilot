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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection-based integration with SonarQube for IDE (formerly SonarLint) plugin.
 * All interaction is via reflection since SonarLint has no public API.
 * Gracefully handles the plugin being absent or API changes.
 */
@SuppressWarnings("java:S3011") // setAccessible is inherent to this reflection-based integration
final class SonarQubeIntegration {

    private static final Logger LOG = Logger.getInstance(SonarQubeIntegration.class);
    private static final String SONAR_PLUGIN_ID = "org.sonarlint.idea";
    private static final String UNKNOWN = "unknown";
    private static final String FINDING_FORMAT = "%s:%d [%s/%s] %s";
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_WAIT_SECONDS = 120;

    private final Project project;

    SonarQubeIntegration(Project project) {
        this.project = project;
    }

    private static ClassLoader getSonarLintClassLoader() {
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId(SONAR_PLUGIN_ID));
        return descriptor != null ? descriptor.getPluginClassLoader() : null;
    }

    private static Class<?> loadSonarClass(String className) throws ClassNotFoundException {
        ClassLoader cl = getSonarLintClassLoader();
        if (cl == null) throw new ClassNotFoundException("SonarLint classloader not available");
        return Class.forName(className, true, cl);
    }

    static boolean isInstalled() {
        return PluginManagerCore.isPluginInstalled(PluginId.getId(SONAR_PLUGIN_ID));
    }

    private boolean isAnalysisRunning() {
        try {
            Class<?> trackerClass = loadSonarClass("org.sonarlint.intellij.analysis.RunningAnalysesTracker");
            Object tracker = project.getService(trackerClass);
            if (tracker != null) {
                Method isEmptyMethod = trackerClass.getMethod("isEmpty");
                return !(boolean) isEmptyMethod.invoke(tracker);
            }
        } catch (Exception e) {
            LOG.debug("Could not check RunningAnalysesTracker: " + e.getMessage());
        }
        return false;
    }

    /**
     * Trigger SonarQube analysis and collect results.
     *
     * @param scope  "all", "changed", or a file path
     * @param limit  max results to return
     * @param offset pagination offset
     * @return formatted findings string
     */
    String runAnalysis(String scope, int limit, int offset) {
        if (!isInstalled()) {
            return "Error: SonarQube for IDE plugin is not installed.";
        }

        try {
            String basePath = project.getBasePath();

            if (isAnalysisRunning()) {
                LOG.info("SonarQube analysis already in progress, waiting for completion");
                List<String> findings = waitForNewResults(basePath);
                return formatOutput(findings, limit, offset);
            }

            String actionId = resolveActionId(scope);
            boolean triggered = triggerAction(actionId);
            if (!triggered) {
                LOG.warn("Could not trigger analysis action '" + actionId + "'");
                return handleUntriggeredAnalysis(basePath, limit, offset);
            }

            List<String> findings = waitForNewResults(basePath);
            return formatOutput(findings, limit, offset);
        } catch (Exception e) {
            LOG.warn("SonarQube analysis failed", e);
            return "Error running SonarQube analysis: " + e.getMessage();
        }
    }

    private String handleUntriggeredAnalysis(String basePath, int limit, int offset) {
        List<String> fallback = collectAllFindings(basePath);
        if (!fallback.isEmpty()) {
            return formatOutput(fallback, limit, offset);
        }
        return "SonarQube analysis could not be triggered. Open the SonarLint Report tab " +
            "and click 'Analyze All Files' manually, then call this tool again.";
    }

    private String resolveActionId(String scope) {
        if ("changed".equalsIgnoreCase(scope)) {
            return "SonarLint.AnalyzeChangedFiles";
        }
        return "SonarLint.AnalyzeAllFiles";
    }

    private boolean triggerAction(String actionId) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted waiting for SonarLint action trigger", e);
            return false;
        } catch (Exception e) {
            LOG.warn("Timeout waiting for SonarLint action trigger", e);
            return false;
        }
    }

    /**
     * Wait for analysis completion using a scheduled poller instead of Thread.sleep.
     * Uses ScheduledExecutorService to poll RunningAnalysesTracker.isEmpty() without
     * blocking a thread during wait intervals.
     * <p>
     * Phase 1: Wait for tracker to become non-empty (modules registered after async trigger).
     * Phase 2: Wait for tracker to become empty (all modules finished).
     */
    private List<String> waitForNewResults(String basePath) {
        try {
            Class<?> trackerClass = loadSonarClass("org.sonarlint.intellij.analysis.RunningAnalysesTracker");
            Object tracker = project.getService(trackerClass);
            Method isEmptyMethod = trackerClass.getMethod("isEmpty");

            if (tracker != null) {
                pollUntilComplete(tracker, isEmptyMethod);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Interrupted while waiting for analysis results");
        } catch (Exception e) {
            LOG.info("RunningAnalysesTracker polling failed: " + e.getMessage());
        }

        return collectOrFallback(basePath);
    }

    /**
     * Poll the tracker using a ScheduledExecutorService instead of Thread.sleep loops.
     */
    private void pollUntilComplete(Object tracker, Method isEmptyMethod)
        throws InterruptedException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean(false);
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;

        var scheduler = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService();
        ScheduledFuture<?> poller = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (System.currentTimeMillis() > deadline) {
                    done.complete(null);
                    return;
                }
                boolean empty = (boolean) isEmptyMethod.invoke(tracker);
                if (!started.get()) {
                    if (!empty) {
                        started.set(true);
                    }
                } else if (empty) {
                    done.complete(null);
                }
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        try {
            done.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            LOG.debug("Polling ended: " + e.getMessage());
        } finally {
            poller.cancel(false);
        }
    }

    private List<String> collectOrFallback(String basePath) {
        List<String> results = collectAllFindings(basePath);
        if (results.isEmpty()) {
            return List.of("Analysis may still be running. Call this tool again to check for results.");
        }
        return results;
    }

    private List<String> collectAllFindings(String basePath) {
        List<String> results = collectFromReportTab(basePath);
        if (results.isEmpty()) {
            results = collectFromOnTheFlyHolder(basePath);
        }
        return results;
    }

    /**
     * Collect findings from ReportTabManager → ReportPanel.lastAnalysisResult → LiveFindings.
     */
    private List<String> collectFromReportTab(String basePath) {
        List<String> results = new ArrayList<>();
        try {
            Object analysisResult = getLatestAnalysisResult();
            if (analysisResult == null) return results;

            Method getFindingsMethod = analysisResult.getClass().getMethod("getFindings");
            Object liveFindings = getFindingsMethod.invoke(analysisResult);
            if (liveFindings == null) return results;

            collectIssuesFromFindings(liveFindings, basePath, results);
            collectHotspotsFromFindings(liveFindings, basePath, results);

            LOG.info("Collected " + results.size() + " SonarQube findings from Report tab");
        } catch (ClassNotFoundException e) {
            LOG.info("ReportTabManager class not found — SonarLint API may have changed");
        } catch (Exception e) {
            LOG.warn("Error collecting from ReportTab", e);
        }
        return results;
    }

    private Object getLatestAnalysisResult() throws Exception {
        Class<?> reportTabManagerClass = loadSonarClass("org.sonarlint.intellij.ui.report.ReportTabManager");
        Object reportTabManager = project.getService(reportTabManagerClass);
        if (reportTabManager == null) {
            LOG.info("ReportTabManager service not available");
            return null;
        }

        Field reportTabsField = reportTabManagerClass.getDeclaredField("reportTabs");
        reportTabsField.setAccessible(true);
        Map<?, ?> reportTabs = (Map<?, ?>) reportTabsField.get(reportTabManager);
        if (reportTabs == null || reportTabs.isEmpty()) {
            LOG.info("No report tabs found");
            return null;
        }

        Object latestPanel = null;
        for (Object panel : reportTabs.values()) {
            latestPanel = panel;
        }
        if (latestPanel == null) return null;

        Field resultField = latestPanel.getClass().getDeclaredField("lastAnalysisResult");
        resultField.setAccessible(true);
        Object analysisResult = resultField.get(latestPanel);
        if (analysisResult == null) {
            LOG.info("ReportPanel has no analysis result yet");
        }
        return analysisResult;
    }

    private void collectIssuesFromFindings(Object liveFindings, String basePath, List<String> results)
        throws Exception {
        Method getIssuesMethod = liveFindings.getClass().getMethod("getIssuesPerFile");
        Map<?, ?> issuesPerFile = (Map<?, ?>) getIssuesMethod.invoke(liveFindings);
        if (issuesPerFile == null) return;

        for (Map.Entry<?, ?> entry : issuesPerFile.entrySet()) {
            Collection<?> issues = (Collection<?>) entry.getValue();
            for (Object issue : issues) {
                String formatted = formatLiveFinding(issue, basePath);
                if (formatted != null) results.add(formatted);
            }
        }
    }

    private void collectHotspotsFromFindings(Object liveFindings, String basePath, List<String> results) {
        try {
            Method getHotspotsMethod = liveFindings.getClass().getMethod("getSecurityHotspotsPerFile");
            Map<?, ?> hotspotsPerFile = (Map<?, ?>) getHotspotsMethod.invoke(liveFindings);
            if (hotspotsPerFile == null) return;

            for (Map.Entry<?, ?> entry : hotspotsPerFile.entrySet()) {
                Collection<?> hotspots = (Collection<?>) entry.getValue();
                for (Object hotspot : hotspots) {
                    String formatted = formatLiveFinding(hotspot, basePath);
                    if (formatted != null) results.add(formatted);
                }
            }
        } catch (NoSuchMethodException e) {
            LOG.info("getSecurityHotspotsPerFile not available");
        } catch (Exception e) {
            LOG.debug("Error collecting hotspots: " + e.getMessage());
        }
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

            collectOnTheFlyIssues(holder, basePath, results);
            collectOnTheFlyHotspots(holder, basePath, results);

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

    private void collectOnTheFlyIssues(Object holder, String basePath, List<String> results)
        throws Exception {
        Method getAllIssues = holder.getClass().getMethod("getAllIssues");
        Collection<?> issues = (Collection<?>) getAllIssues.invoke(holder);
        for (Object issue : issues) {
            String formatted = formatLiveFinding(issue, basePath);
            if (formatted != null) results.add(formatted);
        }
    }

    private void collectOnTheFlyHotspots(Object holder, String basePath, List<String> results) {
        try {
            Method getAllHotspots = holder.getClass().getMethod("getAllHotspots");
            Collection<?> hotspots = (Collection<?>) getAllHotspots.invoke(holder);
            for (Object hotspot : hotspots) {
                String formatted = formatLiveFinding(hotspot, basePath);
                if (formatted != null) results.add(formatted);
            }
        } catch (NoSuchMethodException e) {
            LOG.info("getAllHotspots not available");
        } catch (Exception e) {
            LOG.debug("Error collecting on-the-fly hotspots: " + e.getMessage());
        }
    }

    private String formatLiveFinding(Object finding, String basePath) {
        try {
            Method getMessage = findMethod(finding, "getMessage");
            String message = getMessage != null ? (String) getMessage.invoke(finding) : UNKNOWN;

            Method getRuleKey = findMethod(finding, "getRuleKey");
            String ruleKey = getRuleKey != null ? (String) getRuleKey.invoke(finding) : UNKNOWN;

            String filePath = getFilePath(finding, basePath);
            int line = getLineNumber(finding);
            String severity = getSeverity(finding);

            return String.format(FINDING_FORMAT, filePath, line, severity, ruleKey, message);
        } catch (Exception e) {
            LOG.debug("Could not format finding: " + e.getMessage());
            return null;
        }
    }

    private String getFilePath(Object finding, String basePath) {
        String path = getFilePathFromMethod(finding, "file", basePath);
        if (path != null) return path;

        path = getFilePathViaPsiFile(finding, basePath);
        return path != null ? path : UNKNOWN;
    }

    private String getFilePathFromMethod(Object finding, String methodName, String basePath) {
        try {
            Method fileMethod = findMethod(finding, methodName);
            if (fileMethod != null) {
                Object vf = fileMethod.invoke(finding);
                if (vf instanceof VirtualFile virtualFile) {
                    return relativizePath(virtualFile.getPath(), basePath);
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get file path via " + methodName);
        }
        return null;
    }

    private String getFilePathViaPsiFile(Object finding, String basePath) {
        try {
            Method psiFileMethod = findMethod(finding, "psiFile");
            if (psiFileMethod == null) return null;

            Object psiFile = psiFileMethod.invoke(finding);
            if (psiFile == null) return null;

            Method getVf = findMethod(psiFile, "getVirtualFile");
            if (getVf == null) return null;

            Object vf = getVf.invoke(psiFile);
            if (vf instanceof VirtualFile virtualFile) {
                return relativizePath(virtualFile.getPath(), basePath);
            }
        } catch (Exception e) {
            LOG.debug("Could not get file path via psiFile");
        }
        return null;
    }

    private static String relativizePath(String path, String basePath) {
        if (basePath != null && path.startsWith(basePath)) {
            return path.substring(basePath.length() + 1);
        }
        return path;
    }

    private int getLineNumber(Object finding) {
        try {
            Method getRangeMethod = findMethod(finding, "getRange");
            if (getRangeMethod != null) {
                Object rangeObj = getRangeMethod.invoke(finding);
                if (rangeObj instanceof com.intellij.openapi.editor.RangeMarker rm && rm.isValid()) {
                    return com.intellij.openapi.application.ReadAction.compute(() ->
                        rm.getDocument().getLineNumber(rm.getStartOffset()) + 1
                    );
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get line number from finding");
        }
        return 0;
    }

    private String getSeverity(Object finding) {
        try {
            Method getImpact = findMethod(finding, "getHighestImpact");
            if (getImpact != null) {
                Object impact = getImpact.invoke(finding);
                if (impact != null) return impact.toString();
            }

            Method getSev = findMethod(finding, "getUserSeverity");
            if (getSev != null) {
                Object sev = getSev.invoke(finding);
                if (sev != null) return sev.toString();
            }
        } catch (Exception e) {
            LOG.debug("Could not get severity from finding");
        }
        return "WARNING";
    }

    private static Method findMethod(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                try {
                    Method m = clazz.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // continue up hierarchy
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
