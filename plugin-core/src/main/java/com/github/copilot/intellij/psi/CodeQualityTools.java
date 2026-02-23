package com.github.copilot.intellij.psi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tool handler group for code quality tools: inspections, highlights,
 * quickfixes, suppress, Qodana, optimize imports, format code, and dictionary.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class CodeQualityTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(CodeQualityTools.class);

    // Constants duplicated from PsiBridgeService for use within this handler
    private static final String ERROR_PREFIX = "Error: ";
    private static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    private static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    private static final String ERROR_QODANA_POLLING = "Error polling Qodana results";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_INSPECTION_ID = "inspection_id";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_MESSAGE = "message";
    private static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    private static final String FORMAT_LINES_SUFFIX = " lines)";
    private static final String JAVA_EXTENSION = ".java";
    private static final String LABEL_SUPPRESS_INSPECTION = "Suppress Inspection";
    private static final String JSON_ARTIFACT_LOCATION = "artifactLocation";
    private static final String JSON_REGION = "region";

    // Cached inspection results for pagination
    private List<String> cachedInspectionResults;
    private int cachedInspectionFileCount;
    private String cachedInspectionProfile;
    private long cachedInspectionTimestamp;

    CodeQualityTools(Project project) {
        super(project);
        register("get_problems", this::getProblems);
        register("get_highlights", this::getHighlights);
        register("run_inspections", this::runInspections);
        register("apply_quickfix", this::applyQuickfix);
        register("suppress_inspection", this::suppressInspection);
        register("run_qodana", this::runQodana);
        register("optimize_imports", this::optimizeImports);
        register("format_code", this::formatCode);
        register("add_to_dictionary", this::addToDictionary);
    }

    // ---- get_problems ----

    private String getProblems(JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                ReadAction.run(() -> collectProblems(pathStr, resultFuture));
            } catch (Exception e) {
                resultFuture.complete("Error getting problems: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void collectProblems(String pathStr, CompletableFuture<String> resultFuture) {
        List<VirtualFile> filesToCheck = new ArrayList<>();
        if (!pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            filesToCheck.add(vf);
        } else {
            var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            filesToCheck.addAll(List.of(fem.getOpenFiles()));
        }

        String basePath = project.getBasePath();
        List<String> problems = new ArrayList<>();
        for (VirtualFile vf : filesToCheck) {
            collectProblemsForFile(vf, basePath, problems);
        }

        if (problems.isEmpty()) {
            resultFuture.complete("No problems found"
                + (pathStr.isEmpty() ? " in open files" : " in " + pathStr)
                + ". Analysis is based on IntelliJ's inspections — file must be open in editor for highlights to be available.");
        } else {
            resultFuture.complete(problems.size() + " problems:\n" + String.join("\n", problems));
        }
    }

    private void collectProblemsForFile(VirtualFile vf, String basePath, List<String> problems) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
            doc, project,
            com.intellij.lang.annotation.HighlightSeverity.WARNING,
            0, doc.getTextLength(),
            highlights::add
        );

        for (var h : highlights) {
            if (h.getDescription() == null) continue;
            int line = doc.getLineNumber(h.getStartOffset()) + 1;
            String severity = h.getSeverity().getName();
            problems.add(String.format(FORMAT_LOCATION,
                relPath, line, severity, h.getDescription()));
        }
    }

    // ---- get_highlights ----

    @SuppressWarnings("UnstableApiUsage")
    private String getHighlights(JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                getHighlightsCached(pathStr, limit, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting highlights", e);
                resultFuture.complete("Error getting highlights: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private void getHighlightsCached(String pathStr, int limit, CompletableFuture<String> resultFuture) {
        ReadAction.run(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> allFiles = collectFilesForHighlightAnalysis(pathStr, fileIndex, resultFuture);
            if (resultFuture.isDone()) return;

            LOG.info("Analyzing " + allFiles.size() + " files for highlights (cached mode)");

            List<String> problems = new ArrayList<>();
            int[] counts = analyzeFilesForHighlights(allFiles, limit, problems);

            if (problems.isEmpty()) {
                resultFuture.complete(String.format("No highlights found in %d files analyzed (0 files with issues). " +
                        "Note: This reads cached daemon analysis results from already-analyzed files. " +
                        "For comprehensive code quality analysis, use run_inspections instead.",
                    allFiles.size()));
            } else {
                String summary = String.format("Found %d problems across %d files (showing up to %d):%n%n",
                    counts[0], counts[1], limit);
                resultFuture.complete(summary + String.join("\n", problems));
            }
        });
    }

    private int[] analyzeFilesForHighlights(Collection<VirtualFile> files, int limit, List<String> problems) {
        String basePath = project.getBasePath();
        int totalCount = 0;
        int filesWithProblems = 0;
        for (VirtualFile vf : files) {
            if (totalCount >= limit) break;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                int added = collectFileHighlights(doc, relPath, limit - totalCount, problems);
                if (added > 0) filesWithProblems++;
                totalCount += added;
            }
        }
        return new int[]{totalCount, filesWithProblems};
    }

    private Collection<VirtualFile> collectFilesForHighlightAnalysis(
        String pathStr, ProjectFileIndex fileIndex, CompletableFuture<String> resultFuture) {
        Collection<VirtualFile> files = new ArrayList<>();
        if (pathStr != null && !pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf != null && fileIndex.isInSourceContent(vf)) {
                files.add(vf);
            } else {
                resultFuture.complete("Error: File not found or not in source content: " + pathStr);
                return Collections.emptyList();
            }
        } else {
            fileIndex.iterateContent(file -> {
                if (!file.isDirectory() && fileIndex.isInSourceContent(file)) {
                    files.add(file);
                }
                return true;
            });
        }
        return files;
    }

    private int collectFileHighlights(Document doc, String relPath, int remaining, List<String> problems) {
        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        int added = 0;
        try {
            com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
                doc, project,
                null,
                0, doc.getTextLength(),
                highlights::add
            );

            for (var h : highlights) {
                if (added >= remaining) break;
                var severity = h.getSeverity();
                if (h.getDescription() != null
                    && severity != com.intellij.lang.annotation.HighlightSeverity.INFORMATION
                    && severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal) {
                    int line = doc.getLineNumber(h.getStartOffset()) + 1;
                    problems.add(String.format(FORMAT_LOCATION, relPath, line, severity.getName(), h.getDescription()));
                    added++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to analyze file: " + relPath, e);
        }
        return added;
    }

    // ---- run_inspections ----

    @SuppressWarnings("UnstableApiUsage")
    private String runInspections(JsonObject args) throws Exception {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        String minSeverity = args.has("min_severity") ? args.get("min_severity").getAsString() : null;
        String scopePath = args.has("scope") ? args.get("scope").getAsString() : null;

        if (!com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred()) {
            return "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
        }

        // Serve from cache if available and fresh (5 min TTL)
        long cacheAge = System.currentTimeMillis() - cachedInspectionTimestamp;
        if (offset > 0 && cachedInspectionResults != null && cacheAge < 300_000) {
            LOG.info("Serving inspection page from cache (offset=" + offset + ", cache age=" + cacheAge + "ms)");
            return formatInspectionPage(cachedInspectionResults, cachedInspectionFileCount,
                cachedInspectionProfile, offset, limit);
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        try {
            runInspectionAnalysis(limit, offset, minSeverity, scopePath, resultFuture);
        } catch (Exception e) {
            LOG.error("Error running inspections", e);
            resultFuture.complete("Error running inspections: " + e.getMessage());
        }

        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    private String formatInspectionPage(List<String> allProblems, int filesWithProblems,
                                        String profileName, int offset, int limit) {
        int total = allProblems.size();
        if (total == 0) {
            return "No inspection problems found (cached result).";
        }
        int effectiveOffset = Math.min(offset, total);
        int end = Math.min(effectiveOffset + limit, total);
        List<String> page = allProblems.subList(effectiveOffset, end);
        boolean hasMore = end < total;

        String summary = String.format(
            """
                Found %d total problems across %d files (profile: %s).
                Showing %d-%d of %d.%s
                Results are also visible in the IDE's Inspection Results view.

                """,
            total, filesWithProblems, profileName,
            effectiveOffset + 1, end, total,
            hasMore ? String.format(
                " WARNING: %d more problems not shown! Call run_inspections with offset=%d to see the rest.",
                total - end, end) : "");
        return summary + String.join("\n", page);
    }

    @SuppressWarnings({"TestOnlyProblems", "UnstableApiUsage"})
    private void runInspectionAnalysis(int limit, int offset, String minSeverity,
                                       String scopePath,
                                       CompletableFuture<String> resultFuture) {
        Map<String, Integer> severityRank = Map.of(
            "ERROR", 4, "WARNING", 3, "WEAK_WARNING", 2,
            "LIKE_UNUSED_SYMBOL", 2, "INFORMATION", 1, "INFO", 1,
            "TEXT_ATTRIBUTES", 0, "GENERIC_SERVER_ERROR_OR_WARNING", 3
        );
        final int requiredRank = (minSeverity != null && !minSeverity.isEmpty())
            ? severityRank.getOrDefault(minSeverity.toUpperCase(), 0) : 0;
        try {
            LOG.info("Starting inspection analysis...");

            var inspectionManagerEx = (com.intellij.codeInspection.ex.InspectionManagerEx)
                com.intellij.codeInspection.InspectionManager.getInstance(project);
            var profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project);
            var currentProfile = profileManager.getCurrentProfile();

            com.intellij.analysis.AnalysisScope scope = createAnalysisScope(scopePath, resultFuture);
            if (scope == null) return;

            LOG.info("Using inspection profile: " + currentProfile.getName());

            String basePath = project.getBasePath();
            String profileName = currentProfile.getName();

            GlobalInspectionContextImpl context = new GlobalInspectionContextImpl(
                project, inspectionManagerEx.getContentManager()) {

                @Override
                protected void notifyInspectionsFinished(@NotNull com.intellij.analysis.AnalysisScope scope) {
                    super.notifyInspectionsFinished(scope);
                    final GlobalInspectionContextImpl ctx = this;
                    LOG.info("Inspection analysis completed, collecting results...");
                    LOG.info("Used tools count: " + ctx.getUsedTools().size());

                    // Delay collection to allow inspection tool presentations to fully populate.
                    // notifyInspectionsFinished fires when scope iteration ends, but tool results
                    // may still be written asynchronously. We retry up to 3 times with increasing delay.
                    AppExecutorUtil.getAppExecutorService().submit(() -> {
                        try {
                            InspectionCollectionResult collected = null;
                            for (int attempt = 0; attempt < 3; attempt++) {
                                if (attempt > 0) {
                                    Thread.sleep(500L * attempt);
                                }
                                collected = ReadAction.compute(() ->
                                    collectInspectionProblems(ctx, severityRank, requiredRank, basePath));
                                if (!collected.problems.isEmpty()) break;
                                LOG.info("Inspection collection attempt " + (attempt + 1) +
                                    " found 0 problems, retrying...");
                            }

                            cachedInspectionResults = new ArrayList<>(collected.problems);
                            cachedInspectionFileCount = collected.fileCount;
                            cachedInspectionProfile = profileName;
                            cachedInspectionTimestamp = System.currentTimeMillis();
                            LOG.info("Cached " + collected.problems.size() + " inspection results for pagination" +
                                " (skipped: " + collected.skippedNoDescription + " no-description, " +
                                collected.skippedNoFile + " no-file)");

                            if (collected.problems.isEmpty()) {
                                resultFuture.complete("No inspection problems found. " +
                                    "The code passed all enabled inspections in the current profile (" +
                                    profileName + "). Results are also visible in the IDE's Inspection Results view.");
                            } else {
                                resultFuture.complete(formatInspectionPage(
                                    collected.problems, collected.fileCount, profileName, offset, limit));
                            }
                        } catch (Exception e) {
                            LOG.error("Error collecting inspection results", e);
                            resultFuture.completeExceptionally(e);
                        }
                    });
                }
            };

            context.setExternalProfile(currentProfile);
            context.doInspections(scope);

        } catch (Exception e) {
            LOG.error("Error setting up inspections", e);
            resultFuture.complete("Error setting up inspections: " + e.getMessage());
        }
    }

    private com.intellij.analysis.AnalysisScope createAnalysisScope(
        String scopePath, CompletableFuture<String> resultFuture) {
        if (scopePath == null || scopePath.isEmpty()) {
            LOG.info("Analysis scope: entire project");
            return new com.intellij.analysis.AnalysisScope(project);
        }
        VirtualFile scopeFile = resolveVirtualFile(scopePath);
        if (scopeFile == null) {
            resultFuture.complete("Error: File or directory not found: " + scopePath);
            return null;
        }
        if (scopeFile.isDirectory()) {
            PsiDirectory psiDir = com.intellij.openapi.application.ReadAction.compute(() ->
                PsiManager.getInstance(project).findDirectory(scopeFile)
            );
            if (psiDir == null) {
                resultFuture.complete("Error: Cannot resolve directory: " + scopePath);
                return null;
            }
            LOG.info("Analysis scope: directory " + scopePath);
            return new com.intellij.analysis.AnalysisScope(psiDir);
        }
        PsiFile psiFile = com.intellij.openapi.application.ReadAction.compute(() ->
            PsiManager.getInstance(project).findFile(scopeFile)
        );
        if (psiFile == null) {
            resultFuture.complete(ERROR_PREFIX + ERROR_CANNOT_PARSE + scopePath);
            return null;
        }
        LOG.info("Analysis scope: file " + scopePath);
        return new com.intellij.analysis.AnalysisScope(psiFile);
    }

    // ---- InspectionCollectionResult ----

    private record InspectionCollectionResult(List<String> problems, int fileCount,
                                              int skippedNoDescription, int skippedNoFile) {
    }

    @SuppressWarnings({"UnstableApiUsage", "java:S2583"})
    // null check is defensive against runtime nulls despite @NotNull
    private InspectionCollectionResult collectInspectionProblems(
        GlobalInspectionContextImpl ctx, Map<String, Integer> severityRank,
        int requiredRank, String basePath) {
        List<String> allProblems = new ArrayList<>();
        Set<String> filesSet = new HashSet<>();
        int skippedNoDescription = 0;
        int skippedNoFile = 0;
        int toolsWithProblems = 0;

        for (var tools : ctx.getUsedTools()) {
            var toolWrapper = tools.getTool();
            String toolId = toolWrapper.getShortName();

            var presentation = ctx.getPresentation(toolWrapper);
            //noinspection ConstantValue - presentation can be null at runtime despite @NotNull annotation
            if (presentation == null) continue;

            int beforeSize = allProblems.size();
            int[] skipped = collectProblemsFromTool(presentation, toolId, basePath,
                filesSet, severityRank, requiredRank, allProblems);
            skippedNoDescription += skipped[0];
            skippedNoFile += skipped[1];
            if (allProblems.size() > beforeSize) {
                toolsWithProblems++;
                LOG.info("Inspection tool '" + toolId + "' found " +
                    (allProblems.size() - beforeSize) + " problems");
            }
        }
        LOG.info("Total: " + allProblems.size() + " problems from " + toolsWithProblems +
            " tools (out of " + ctx.getUsedTools().size() + " used tools)");
        return new InspectionCollectionResult(allProblems, filesSet.size(), skippedNoDescription, skippedNoFile);
    }

    private int[] collectProblemsFromTool(
        com.intellij.codeInspection.ui.InspectionToolPresentation presentation,
        String toolId, String basePath, Set<String> filesSet,
        Map<String, Integer> severityRank, int requiredRank, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;

        // Try getProblemElements first (has file associations via RefEntity)
        var problemElements = presentation.getProblemElements();
        //noinspection ConstantValue - problemElements can be null at runtime despite @NotNull annotation
        if (problemElements != null && !problemElements.isEmpty()) {
            for (var refEntity : problemElements.keys()) {
                var descriptors = problemElements.get(refEntity);
                if (descriptors == null) continue;

                for (var descriptor : descriptors) {
                    int result = processInspectionDescriptor(
                        descriptor, refEntity, toolId, basePath, filesSet, severityRank, requiredRank, allProblems);
                    if (result == 1) skippedNoDescription++;
                    else if (result == 2) skippedNoFile++;
                }
            }
            return new int[]{skippedNoDescription, skippedNoFile};
        }

        // Try getProblemDescriptors() for tools that don't use RefEntity
        var flatDescriptors = presentation.getProblemDescriptors();
        for (var descriptor : flatDescriptors) {
            int result = processInspectionDescriptor(
                descriptor, null, toolId, basePath, filesSet, severityRank, requiredRank, allProblems);
            if (result == 1) skippedNoDescription++;
            else if (result == 2) skippedNoFile++;
        }

        // Fallback: for tools like UnusedDeclarationPresentation that store results
        // in the reference graph, use exportResults() to extract XML and parse it
        var hasProblems = presentation.hasReportedProblems();
        if (hasProblems == com.intellij.util.ThreeState.YES) {
            try {
                presentation.updateContent();
                var elements = new ArrayList<org.jdom.Element>();
                presentation.exportResults(elements::add, entity -> true, desc -> true);

                // If bulk export produced nothing, try per-entity export via getContent()
                if (elements.isEmpty()) {
                    var content = presentation.getContent();
                    for (var entry : content.entrySet()) {
                        for (var refEntity : entry.getValue()) {
                            presentation.exportResults(elements::add, refEntity, desc -> true);
                        }
                    }
                }

                for (var element : elements) {
                    String formatted = formatExportedElement(element, toolId, basePath, filesSet);
                    if (formatted != null && !formatted.isEmpty()) {
                        if (requiredRank > 0) {
                            String severity = extractSeverityFromElement(element);
                            int rank = severityRank.getOrDefault(severity.toUpperCase(), 0);
                            if (rank < requiredRank) continue;
                        }
                        allProblems.add(formatted);
                    } else if (formatted == null) {
                        skippedNoDescription++;
                    } else {
                        skippedNoFile++;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to export results for tool '" + toolId + "': " + e.getMessage());
            }
        }

        return new int[]{skippedNoDescription, skippedNoFile};
    }

    /**
     * Parse an exported XML Element (from exportResults) to produce a problem string.
     * Element structure: {@code <problem><file>...</file><line>...</line>
     * <problem_class severity="WARNING">...</problem_class><description>...</description></problem>}
     */
    private String formatExportedElement(org.jdom.Element element, String toolId,
                                         String basePath, Set<String> filesSet) {
        String description = element.getChildText("description");
        if (description == null || description.isEmpty()) return null;

        String fileUrl = element.getChildText("file");
        if (fileUrl == null || fileUrl.isEmpty()) return "";

        // Convert file URL format: "file://$PROJECT_DIR$/path/to/file" -> relative path
        String filePath = fileUrl;
        if (filePath.contains("$PROJECT_DIR$")) {
            filePath = filePath.replaceAll(".*\\$PROJECT_DIR\\$/", "");
        } else if (filePath.startsWith("file://")) {
            filePath = filePath.substring(7);
            if (basePath != null) {
                filePath = relativize(basePath, filePath);
            }
        }
        filesSet.add(filePath);

        String lineStr = element.getChildText("line");
        int line = 0;
        if (lineStr != null) {
            try {
                line = Integer.parseInt(lineStr);
            } catch (NumberFormatException ignored) { /* empty */ }
        }

        String severity = extractSeverityFromElement(element);

        // Clean up HTML tags in description
        description = description.replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();

        return String.format("%s:%d [%s/%s] %s", filePath, line, severity, toolId, description);
    }

    private String extractSeverityFromElement(org.jdom.Element element) {
        var problemClass = element.getChild("problem_class");
        if (problemClass != null) {
            String severity = problemClass.getAttributeValue("severity");
            if (severity != null) return severity;
        }
        return "WARNING";
    }

    /**
     * Process a single inspection descriptor and add to allProblems if valid.
     *
     * @return 0=added, 1=skippedNoDescription, 2=skippedNoFile, 3=filtered by severity
     */
    private int processInspectionDescriptor(
        com.intellij.codeInspection.CommonProblemDescriptor descriptor,
        com.intellij.codeInspection.reference.RefEntity refEntity,
        String toolId, String basePath, Set<String> filesSet,
        Map<String, Integer> severityRank, int requiredRank, List<String> allProblems) {
        String formatted = formatInspectionDescriptor(descriptor, refEntity, toolId, basePath, filesSet);
        if (formatted == null) return 1;
        if (formatted.isEmpty()) return 2;

        String severity = (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd)
            ? pd.getHighlightType().toString() : "WARNING";
        if (requiredRank > 0) {
            int rank = severityRank.getOrDefault(severity.toUpperCase(), 0);
            if (rank < requiredRank) return 3;
        }

        allProblems.add(formatted);
        return 0;
    }

    @SuppressWarnings("java:S2589") // null check is defensive against runtime nulls despite @NotNull
    private String formatInspectionDescriptor(
        com.intellij.codeInspection.CommonProblemDescriptor descriptor,
        com.intellij.codeInspection.reference.RefEntity refEntity,
        String toolId, String basePath, Set<String> filesSet) {
        String description = descriptor.getDescriptionTemplate();
        //noinspection ConstantValue - description can be null at runtime despite @NotNull annotation
        if (description == null || description.isEmpty()) return null;

        String refText = "";
        int line = 0;
        String filePath = "";

        if (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd) {
            var psiEl = pd.getPsiElement();
            if (psiEl != null) {
                refText = psiEl.getText();
                if (refText != null && refText.length() > 80) {
                    refText = refText.substring(0, 80) + "...";
                }
            }
            line = pd.getLineNumber() + 1;
            filePath = resolveDescriptorFilePath(pd, basePath, filesSet);
        } else if (refEntity != null) {
            // For global inspections (unused, module dependency, etc.), extract file from RefEntity
            filePath = resolveRefEntityFilePath(refEntity, basePath, filesSet);
            refText = refEntity.getName();
        }

        description = description.replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("#ref", refText != null ? refText : "").replace("#loc", "").trim();

        if (description.isEmpty()) return null;
        if (filePath.isEmpty()) return ""; // sentinel for skippedNoFile

        String severity = (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd2)
            ? pd2.getHighlightType().toString() : "WARNING";

        return String.format("%s:%d [%s/%s] %s", filePath, line, severity, toolId, description);
    }

    private String resolveRefEntityFilePath(com.intellij.codeInspection.reference.RefEntity refEntity,
                                            String basePath, Set<String> filesSet) {
        if (refEntity instanceof com.intellij.codeInspection.reference.RefElement refElement) {
            var psiElement = refElement.getPsiElement();
            if (psiElement != null) {
                var containingFile = psiElement.getContainingFile();
                if (containingFile != null) {
                    var vf = containingFile.getVirtualFile();
                    if (vf != null) {
                        String filePath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                        filesSet.add(filePath);
                        return filePath;
                    }
                }
            }
        }
        // For module-level problems (RefModuleImpl), use "[module:name]" as file path
        if (refEntity instanceof com.intellij.codeInspection.reference.RefModule refModule) {
            String moduleName = refModule.getName();
            String moduleLabel = "[module:" + moduleName + "]";
            filesSet.add(moduleLabel);
            return moduleLabel;
        }
        return "";
    }

    private String resolveDescriptorFilePath(com.intellij.codeInspection.ProblemDescriptor pd,
                                             String basePath, Set<String> filesSet) {
        var psiElement = pd.getPsiElement();
        if (psiElement == null) return "";
        var containingFile = psiElement.getContainingFile();
        if (containingFile == null) return "";
        var vf = containingFile.getVirtualFile();
        if (vf == null) return "";
        String filePath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
        filesSet.add(filePath);
        return filePath;
    }

    // ---- add_to_dictionary ----

    private String addToDictionary(JsonObject args) throws Exception {
        String word = args.get("word").getAsString().trim().toLowerCase();
        if (word.isEmpty()) {
            return "Error: word cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var spellChecker = com.intellij.spellchecker.SpellCheckerManager.getInstance(project);
                spellChecker.acceptWordAsCorrect(word, project);
                resultFuture.complete("Added '" + word + "' to project dictionary. " +
                    "It will no longer be flagged as a typo in future inspections.");
            } catch (Exception e) {
                LOG.error("Error adding word to dictionary", e);
                resultFuture.complete(ERROR_PREFIX + "adding word to dictionary: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    // ---- suppress_inspection ----

    private String suppressInspection(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        int line = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString().trim();

        if (inspectionId.isEmpty()) {
            return ERROR_PREFIX + PARAM_INSPECTION_ID + " cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                processSuppressInspection(pathStr, line, inspectionId, resultFuture);
            } catch (Exception e) {
                LOG.error("Error suppressing inspection", e);
                resultFuture.complete("Error suppressing inspection: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void processSuppressInspection(String pathStr, int line, String inspectionId,
                                           CompletableFuture<String> resultFuture) {
        var vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            resultFuture.complete("Error: file not found: " + pathStr);
            return;
        }

        var psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            resultFuture.complete("Error: could not parse file: " + pathStr);
            return;
        }

        var document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            resultFuture.complete("Error: could not get document for: " + pathStr);
            return;
        }

        int zeroLine = line - 1;
        if (zeroLine < 0 || zeroLine >= document.getLineCount()) {
            resultFuture.complete("Error: line " + line + " is out of range (file has " +
                document.getLineCount() + FORMAT_LINES_SUFFIX);
            return;
        }

        int offset = document.getLineStartOffset(zeroLine);
        var element = psiFile.findElementAt(offset);
        if (element == null) {
            resultFuture.complete("Error: no code element found at line " + line);
            return;
        }

        var target = findSuppressTarget(element);
        String fileName = vf.getName();

        if (fileName.endsWith(JAVA_EXTENSION)) {
            resultFuture.complete(suppressJava(target, inspectionId, document));
        } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            resultFuture.complete(suppressKotlin(target, inspectionId, document));
        } else {
            resultFuture.complete(suppressWithComment(target, inspectionId, document));
        }
    }

    private com.intellij.psi.PsiElement findSuppressTarget(com.intellij.psi.PsiElement element) {
        var current = element;
        while (current != null) {
            if (current instanceof com.intellij.psi.PsiMethod ||
                current instanceof com.intellij.psi.PsiField ||
                current instanceof com.intellij.psi.PsiClass ||
                current instanceof com.intellij.psi.PsiLocalVariable) {
                return current;
            }
            if (current instanceof com.intellij.psi.PsiStatement) {
                return current;
            }
            current = current.getParent();
        }
        return element;
    }

    private String suppressJava(com.intellij.psi.PsiElement target, String inspectionId,
                                com.intellij.openapi.editor.Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        if (target instanceof com.intellij.psi.PsiModifierListOwner modListOwner) {
            var modList = modListOwner.getModifierList();
            if (modList != null) {
                var existing = modList.findAnnotation("java.lang.SuppressWarnings");
                if (existing != null) {
                    return addToExistingSuppressWarnings(existing, inspectionId, document);
                }
            }
        }

        String annotation = indent + "@SuppressWarnings(\"" + inspectionId + "\")\n";
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @SuppressWarnings(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String addToExistingSuppressWarnings(com.intellij.psi.PsiAnnotation annotation,
                                                 String inspectionId,
                                                 com.intellij.openapi.editor.Document document) {
        String text = annotation.getText();
        if (text.contains(inspectionId)) {
            return "Inspection '" + inspectionId + "' is already suppressed at this location";
        }

        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                var value = annotation.findAttributeValue("value");
                if (value != null) {
                    if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue) {
                        int endBrace = value.getTextRange().getEndOffset() - 1;
                        document.insertString(endBrace, ", \"" + inspectionId + "\"");
                    } else {
                        var range = value.getTextRange();
                        String existing = document.getText(range);
                        document.replaceString(range.getStartOffset(), range.getEndOffset(),
                            "{" + existing + ", \"" + inspectionId + "\"}");
                    }
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added '" + inspectionId + "' to existing @SuppressWarnings annotation";
    }

    private String suppressKotlin(com.intellij.psi.PsiElement target, String inspectionId,
                                  com.intellij.openapi.editor.Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        if (targetLine > 0) {
            int prevStart = document.getLineStartOffset(targetLine - 1);
            int prevEnd = document.getLineEndOffset(targetLine - 1);
            String prevLine = document.getText(
                new com.intellij.openapi.util.TextRange(prevStart, prevEnd)).trim();
            if (prevLine.startsWith("@Suppress(") && prevLine.contains(inspectionId)) {
                return "Inspection '" + inspectionId + "' is already suppressed at this location";
            }
        }

        String annotation = indent + "@Suppress(\"" + inspectionId + "\")\n";
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @Suppress(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private String suppressWithComment(com.intellij.psi.PsiElement target, String inspectionId,
                                       com.intellij.openapi.editor.Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }

        String comment = indent + "//noinspection " + inspectionId + "\n";
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, comment);
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added //noinspection " + inspectionId + " comment at line " + (targetLine + 1);
    }

    // ---- run_qodana ----

    @SuppressWarnings("OverrideOnly")
    private String runQodana(JsonObject args) throws Exception {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance();
                var qodanaAction = actionManager.getAction("Qodana.RunQodanaAction");

                if (qodanaAction == null) {
                    resultFuture.complete("Error: Qodana plugin is not installed or not available. " +
                        "Install it from Settings > Plugins > Marketplace.");
                    return;
                }

                var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build();
                var presentation = qodanaAction.getTemplatePresentation().clone();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    dataContext, presentation, "QodanaTool",
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);

                qodanaAction.update(event);
                if (!event.getPresentation().isEnabled()) {
                    resultFuture.complete("Error: Qodana action is not available. " +
                        "The project may not be fully loaded yet, or Qodana may already be running.");
                    return;
                }

                LOG.info("Triggering Qodana local analysis...");
                qodanaAction.actionPerformed(event);

                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        pollQodanaResults(limit, resultFuture);
                    } catch (Exception e) {
                        LOG.error(ERROR_QODANA_POLLING, e);
                        resultFuture.complete("Qodana analysis was triggered but result polling failed: " +
                            e.getMessage() + ". Check the Qodana tab in the Problems tool window for results.");
                    }
                });

            } catch (Exception e) {
                LOG.error("Error triggering Qodana", e);
                resultFuture.complete("Error triggering Qodana: " + e.getMessage());
            }
        });

        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    private void pollQodanaResults(int limit, CompletableFuture<String> resultFuture) {
        try {
            Class<?> serviceClass = loadQodanaServiceClass(limit, resultFuture);
            if (serviceClass == null) return;

            Object qodanaService = getQodanaServiceInstance(serviceClass, limit, resultFuture);
            if (qodanaService == null) return;

            waitForQodanaCompletion(qodanaService, serviceClass, limit, resultFuture);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        } catch (Exception e) {
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        }
    }

    private Class<?> loadQodanaServiceClass(int limit, CompletableFuture<String> resultFuture)
        throws InterruptedException {
        try {
            return Class.forName("org.jetbrains.qodana.run.QodanaRunInIdeService");
        } catch (ClassNotFoundException e) {
            LOG.info("QodanaRunInIdeService not available, waiting for SARIF output...");
            for (int i = 0; i < 300; i++) {
                String fallbackResult = tryFindSarifOutput(limit);
                if (fallbackResult != null) {
                    resultFuture.complete(fallbackResult);
                    return null;
                }
                Thread.sleep(1000);
            }
            resultFuture.complete("Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                "(Qodana service class not available for result polling)");
            return null;
        }
    }

    private Object getQodanaServiceInstance(Class<?> serviceClass, int limit,
                                            CompletableFuture<String> resultFuture) {
        Object qodanaService = project.getService(serviceClass);
        if (qodanaService == null) {
            String fallbackResult = tryFindSarifOutput(limit);
            resultFuture.complete(Objects.requireNonNullElse(fallbackResult,
                "Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                    "(Could not access Qodana service to poll results)"));
            return null;
        }
        return qodanaService;
    }

    private void waitForQodanaCompletion(Object qodanaService, Class<?> serviceClass,
                                         int limit, CompletableFuture<String> resultFuture)
        throws Exception {
        var getRunState = serviceClass.getMethod("getRunState");
        var runStateFlow = getRunState.invoke(qodanaService);
        var getValueMethod = runStateFlow.getClass().getMethod("getValue");

        boolean completed = pollQodanaRunState(getValueMethod, runStateFlow, resultFuture);
        if (!completed) return;

        tryReadQodanaSarifResults(qodanaService, serviceClass, getValueMethod, limit, resultFuture);
    }

    private boolean pollQodanaRunState(java.lang.reflect.Method getValueMethod, Object runStateFlow,
                                       CompletableFuture<String> resultFuture) throws Exception {
        int maxPolls = 480;
        boolean wasRunning = false;
        for (int i = 0; i < maxPolls; i++) {
            var state = getValueMethod.invoke(runStateFlow);
            String stateName = state.getClass().getSimpleName();

            if (stateName.contains("Running")) {
                wasRunning = true;
                if (i % 30 == 0) {
                    LOG.info("Qodana analysis still running... (" + i + "s)");
                }
            } else if (wasRunning) {
                LOG.info("Qodana analysis completed after ~" + i + "s");
                return true;
            } else if (i > 10) {
                resultFuture.complete("Qodana analysis was triggered but may require user interaction. " +
                    "Check the IDE for any Qodana dialogs or the Qodana tab in Problems for results.");
                return false;
            }
            Thread.sleep(1000);
        }
        return true;
    }

    private void tryReadQodanaSarifResults(Object qodanaService, Class<?> serviceClass,
                                           java.lang.reflect.Method getValueMethod, int limit,
                                           CompletableFuture<String> resultFuture) throws Exception {
        var getRunsResults = serviceClass.getMethod("getRunsResults");
        var runsResultsFlow = getRunsResults.invoke(qodanaService);
        var outputs = (Set<?>) getValueMethod.invoke(runsResultsFlow);
        if (outputs != null && !outputs.isEmpty()) {
            var latest = outputs.iterator().next();
            var getSarifPath = latest.getClass().getMethod("getSarifPath");
            var sarifPath = (java.nio.file.Path) getSarifPath.invoke(latest);
            if (sarifPath != null && java.nio.file.Files.exists(sarifPath)) {
                String sarif = java.nio.file.Files.readString(sarifPath);
                resultFuture.complete(parseSarifResults(sarif, limit));
                return;
            }
        }
        resultFuture.complete("Qodana analysis completed. Results are visible in the Qodana tab " +
            "of the Problems tool window. (SARIF output file not found for programmatic reading)");
    }

    private String tryFindSarifOutput(int limit) {
        String basePath = project.getBasePath();

        String result = tryCommonSarifLocations(basePath, limit);
        if (result != null) return result;

        return searchQodanaDirForSarif(basePath, limit);
    }

    private String tryCommonSarifLocations(String basePath, int limit) {
        java.nio.file.Path[] candidates = {
            basePath != null ? java.nio.file.Path.of(basePath, ".qodana", "results", "qodana.sarif.json") : null,
            java.nio.file.Path.of("/tmp/qodana_output/qodana.sarif.json"),
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "qodana_output", "qodana.sarif.json"),
        };
        for (var candidate : candidates) {
            if (candidate != null && java.nio.file.Files.exists(candidate)) {
                try {
                    String sarif = java.nio.file.Files.readString(candidate);
                    if (sarif.length() > 10) {
                        LOG.info("Found Qodana SARIF output at candidate path: " + candidate);
                        return parseSarifResults(sarif, limit);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to read SARIF file at " + candidate, e);
                }
            }
        }
        return null;
    }

    private String searchQodanaDirForSarif(String basePath, int limit) {
        if (basePath == null) return null;

        try {
            var qodanaDir = java.nio.file.Path.of(basePath, ".qodana");
            if (!java.nio.file.Files.isDirectory(qodanaDir)) return null;

            try (var stream = java.nio.file.Files.walk(qodanaDir, 5)) {
                var sarifFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sarif.json"))
                    .min((a, b) -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(b)
                                .compareTo(java.nio.file.Files.getLastModifiedTime(a));
                        } catch (Exception e) {
                            return 0;
                        }
                    });
                if (sarifFile.isPresent()) {
                    String sarif = java.nio.file.Files.readString(sarifFile.get());
                    LOG.info("Found Qodana SARIF output via recursive search: " + sarifFile.get());
                    return parseSarifResults(sarif, limit);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching for SARIF files", e);
        }
        return null;
    }

    private String parseSarifResults(String sarifJson, int limit) {
        try {
            var sarif = JsonParser.parseString(sarifJson).getAsJsonObject();
            var runs = sarif.getAsJsonArray("runs");
            if (runs == null || runs.isEmpty()) {
                return "Qodana completed but no analysis runs found in SARIF output.";
            }

            List<String> problems = new ArrayList<>();
            Set<String> filesSet = new HashSet<>();
            String basePath = project.getBasePath();

            for (var runElement : runs) {
                collectSarifRunProblems(runElement.getAsJsonObject(), basePath, limit, problems, filesSet);
            }

            if (problems.isEmpty()) {
                return "Qodana analysis completed: no problems found. " +
                    "Results are also visible in the Qodana tab of the Problems tool window.";
            }

            String summary = String.format(
                """
                    Qodana found %d problems across %d files (showing up to %d).
                    Results are also visible in the Qodana tab of the Problems tool window.

                    """,
                problems.size(), filesSet.size(), limit);
            return summary + String.join("\n", problems);

        } catch (Exception e) {
            LOG.error("Error parsing SARIF results", e);
            return "Qodana analysis completed but SARIF parsing failed: " + e.getMessage() +
                ". Check the Qodana tab in the Problems tool window for results.";
        }
    }

    private void collectSarifRunProblems(JsonObject run, String basePath,
                                         int limit, List<String> problems, Set<String> filesSet) {
        var results = run.getAsJsonArray("results");
        if (results == null) return;

        for (var resultElement : results) {
            if (problems.size() >= limit) break;
            var result = resultElement.getAsJsonObject();

            String ruleId = result.has("ruleId") ? result.get("ruleId").getAsString() : "unknown";
            String level = result.has(PARAM_LEVEL) ? result.get(PARAM_LEVEL).getAsString() : "warning";
            String message = extractSarifMessage(result);
            SarifLocation loc = extractSarifLocation(result, basePath);

            if (!loc.filePath.isEmpty()) filesSet.add(loc.filePath);
            problems.add(String.format("%s:%d [%s/%s] %s", loc.filePath, loc.line, level, ruleId, message));
        }
    }

    // ---- SarifLocation ----

    private record SarifLocation(String filePath, int line) {
    }

    private String extractSarifMessage(JsonObject result) {
        if (result.has(PARAM_MESSAGE) && result.getAsJsonObject(PARAM_MESSAGE).has("text")) {
            return result.getAsJsonObject(PARAM_MESSAGE).get("text").getAsString();
        }
        return "";
    }

    private SarifLocation extractSarifLocation(JsonObject result, String basePath) {
        String filePath = "";
        int line = -1;
        if (!result.has("locations")) return new SarifLocation(filePath, line);

        var locations = result.getAsJsonArray("locations");
        if (locations.isEmpty()) return new SarifLocation(filePath, line);

        var loc = locations.get(0).getAsJsonObject();
        if (!loc.has("physicalLocation")) return new SarifLocation(filePath, line);

        var phys = loc.getAsJsonObject("physicalLocation");
        if (phys.has(JSON_ARTIFACT_LOCATION) &&
            phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).has("uri")) {
            filePath = phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).get("uri").getAsString();
            if (filePath.startsWith("file://")) filePath = filePath.substring(7);
            if (basePath != null) filePath = relativize(basePath, filePath);
        }
        if (phys.has(JSON_REGION) &&
            phys.getAsJsonObject(JSON_REGION).has("startLine")) {
            line = phys.getAsJsonObject(JSON_REGION).get("startLine").getAsInt();
        }
        return new SarifLocation(filePath, line);
    }

    // ---- optimize_imports ----

    private String optimizeImports(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                    }, "Optimize Imports", null)
                );

                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), vf.getPath()) : pathStr;
                resultFuture.complete("Imports optimized: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error optimizing imports: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    // ---- format_code ----

    private String formatCode(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                    }, "Reformat Code", null)
                );

                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), vf.getPath()) : pathStr;
                resultFuture.complete("Code formatted: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error formatting code: " + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    // ---- apply_quickfix ----

    private String applyQuickfix(JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_INSPECTION_ID)) {
            return "Error: 'file', 'line', and '" + PARAM_INSPECTION_ID + "' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString();
        int fixIndex = args.has("fix_index") ? args.get("fix_index").getAsInt() : 0;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ERROR_PREFIX + ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        resultFuture.complete(executeQuickfix(vf, pathStr, targetLine, inspectionId, fixIndex));
                    } catch (Exception e) {
                        LOG.warn("Error applying quickfix", e);
                        resultFuture.complete("Error applying quickfix: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                LOG.warn("Error in applyQuickfix", e);
                resultFuture.complete(ERROR_PREFIX + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private String executeQuickfix(VirtualFile vf, String pathStr, int targetLine,
                                   String inspectionId, int fixIndex) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ERROR_PREFIX + ERROR_CANNOT_PARSE + pathStr;

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > document.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + document.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        int lineStartOffset = document.getLineStartOffset(targetLine - 1);
        int lineEndOffset = document.getLineEndOffset(targetLine - 1);

        var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager
            .getInstance(project).getCurrentProfile();
        var toolWrapper = profile.getInspectionTool(inspectionId, project);

        if (toolWrapper == null) {
            return "Error: Inspection '" + inspectionId + "' not found. " +
                "Use the inspection ID from run_inspections output (e.g., 'RedundantCast', 'unused').";
        }

        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems =
            findProblemsOnLine(toolWrapper.getTool(), psiFile, lineStartOffset, lineEndOffset);

        if (lineProblems.isEmpty()) {
            return "No problems found for inspection '" + inspectionId + "' at line " + targetLine +
                " in " + pathStr + ". The inspection may have been resolved, or it may be a global inspection " +
                "that doesn't support quickfixes. Try using intellij_write_file instead.";
        }

        return applyAndReportFix(lineProblems, fixIndex, pathStr, targetLine);
    }

    private List<com.intellij.codeInspection.ProblemDescriptor> findProblemsOnLine(
        com.intellij.codeInspection.InspectionProfileEntry tool, PsiFile psiFile,
        int lineStartOffset, int lineEndOffset) {
        List<com.intellij.codeInspection.ProblemDescriptor> problems = new ArrayList<>();
        if (tool instanceof com.intellij.codeInspection.LocalInspectionTool localTool) {
            var inspectionManager = com.intellij.codeInspection.InspectionManager.getInstance(project);
            var holder = new com.intellij.codeInspection.ProblemsHolder(inspectionManager, psiFile, false);
            var visitor = localTool.buildVisitor(holder, false);
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    element.accept(visitor);
                    super.visitElement(element);
                }
            });
            problems.addAll(holder.getResults());
        }

        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems = new ArrayList<>();
        for (var problem : problems) {
            PsiElement elem = problem.getPsiElement();
            if (elem != null) {
                int offset = elem.getTextOffset();
                if (offset >= lineStartOffset && offset <= lineEndOffset) {
                    lineProblems.add(problem);
                }
            }
        }
        return lineProblems;
    }

    private String applyAndReportFix(List<com.intellij.codeInspection.ProblemDescriptor> lineProblems,
                                     int fixIndex, String pathStr, int targetLine) {
        com.intellij.codeInspection.ProblemDescriptor targetProblem =
            lineProblems.get(Math.min(fixIndex, lineProblems.size() - 1));

        var fixes = targetProblem.getFixes();
        if (fixes == null || fixes.length == 0) {
            return "No quickfixes available for this problem. Description: " +
                targetProblem.getDescriptionTemplate() + ". Use intellij_write_file to fix manually.";
        }

        var fix = fixes[Math.min(fixIndex, fixes.length - 1)];

        //noinspection unchecked
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            () -> fix.applyFix(project, targetProblem),
            "Apply Quick Fix: " + fix.getName(),
            null
        );

        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        StringBuilder sb = new StringBuilder();
        sb.append("? Applied fix: ").append(fix.getName()).append("\n");
        sb.append("  File: ").append(pathStr).append(" line ").append(targetLine).append("\n");
        if (fixes.length > 1) {
            sb.append("  (").append(fixes.length).append(" fixes were available, applied #")
                .append(Math.min(fixIndex, fixes.length - 1)).append(")\n");
            sb.append("  Other available fixes:\n");
            for (int i = 0; i < fixes.length; i++) {
                if (i != Math.min(fixIndex, fixes.length - 1)) {
                    sb.append("    ").append(i).append(": ").append(fixes[i].getName()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
