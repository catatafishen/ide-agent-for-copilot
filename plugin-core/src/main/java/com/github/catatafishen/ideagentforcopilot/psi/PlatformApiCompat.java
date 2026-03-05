package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.GlobalInspectionContextEx;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Centralizes all IntelliJ Platform API calls that produce false-positive "cannot resolve"
 * errors in the IDE editor. These errors occur because the development IDE (running the plugin)
 * uses a different platform version than the target SDK configured in Gradle.
 *
 * <p>The Gradle build compiles cleanly against the target SDK. The IDE's daemon analyzer, however,
 * resolves symbols against its own bundled platform JARs, which may have different method
 * signatures, generics, or extension point APIs. This is a well-known issue when developing
 * IntelliJ plugins inside an IDE whose version differs from the target platform.</p>
 *
 * <p>By isolating these calls here, the rest of the codebase stays error-free in the editor,
 * and each compatibility concern is documented in one place.</p>
 */
final class PlatformApiCompat {

    private static final Logger LOG = Logger.getInstance(PlatformApiCompat.class);

    private PlatformApiCompat() {
    }

    /**
     * Collects text from editor notification banners (e.g., "Some directories are not excluded").
     *
     * <p><b>Why extracted:</b> Three API calls on this path produce false-positive errors in the IDE:</p>
     * <ul>
     *   <li>{@code EditorNotificationProvider.EP_NAME.getExtensions(project)} — the IDE cannot resolve
     *       {@code getExtensions(Project)} because {@code ProjectExtensionPointName} generics differ
     *       between the dev IDE and target platform versions.</li>
     *   <li>{@code provider.collectNotificationData(project, vf)} — cascading unresolved type from
     *       the extension point lookup above.</li>
     *   <li>{@code factory.apply(editor)} — same cascading issue; the {@code Function} return type
     *       is inferred as unknown.</li>
     * </ul>
     *
     * <p>All three methods exist and work correctly at runtime. The Gradle build compiles without errors.</p>
     */
    static @NotNull List<String> collectEditorNotificationTexts(
        @NotNull Project project, @NotNull VirtualFile vf, @NotNull FileEditor editor) {
        List<String> notifications = new ArrayList<>();
        for (var provider : EditorNotificationProvider.EP_NAME.getExtensions(project)) {
            try {
                Function<? super FileEditor, ? extends JComponent> factory =
                    provider.collectNotificationData(project, vf);
                if (factory == null) continue;
                JComponent panel = factory.apply(editor);
                if (panel instanceof EditorNotificationPanel enp) {
                    String text = enp.getText();
                    if (text != null && !text.isEmpty()) {
                        notifications.add("[BANNER] " + text);
                    }
                }
            } catch (Exception e) {
                // Skip failing providers — some may not be compatible with the current context
            }
        }
        return notifications;
    }

    /**
     * Retrieves the inspection presentation for a tool wrapper, safely handling constructor
     * mismatches in third-party inspection plugins.
     *
     * <p><b>Why extracted:</b> {@code GlobalInspectionContextEx.getPresentation()} internally calls
     * {@code createPresentation()}, which uses reflection to instantiate presentation classes.
     * Some bundled plugins (e.g., the Duplicates detector) change their constructor signature
     * across IDE versions. When the running IDE version differs from the target platform,
     * this throws {@code NoSuchMethodException} wrapped in {@code RuntimeException}.</p>
     *
     * <p>This wrapper catches the reflection failure and returns null, allowing the caller
     * to skip the incompatible tool gracefully instead of aborting the entire inspection run.</p>
     */
    static @Nullable InspectionToolResultExporter getInspectionPresentation(
        @NotNull GlobalInspectionContextEx ctx, @NotNull InspectionToolWrapper<?, ?> toolWrapper) {
        try {
            return ctx.getPresentation(toolWrapper);
        } catch (RuntimeException e) {
            // Constructor mismatch in a third-party inspection plugin's presentation class.
            // Common with DuplicateInspectionPresentation when IDE version != target platform.
            LOG.debug("Skipping inspection tool '" + toolWrapper.getShortName()
                + "' — presentation class incompatible: " + e.getMessage());
            return null;
        }
    }

    /**
     * Looks up a service by raw {@code Class<?>} on a project, returning it as {@code Object}.
     *
     * <p><b>Why extracted:</b> {@code Project.getService(Class<T>)} expects a concrete type parameter.
     * When called with {@code Class<?>} (e.g., a reflectively loaded Qodana service class),
     * the IDE's type checker cannot resolve the method because the wildcard type doesn't match
     * the bounded generic {@code <T>}. The Gradle compiler handles this correctly via erasure.</p>
     *
     * <p>This is used for optional integrations (Qodana) where the service class may not exist
     * at compile time and must be loaded by name.</p>
     */
    @SuppressWarnings("unchecked")
    static @Nullable Object getServiceByRawClass(@NotNull Project project, @NotNull Class<?> serviceClass) {
        // Cast to Class<Object> satisfies the generic bound; safe because we only use the result as Object.
        return project.getService((Class<Object>) serviceClass);
    }
}
