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
public final class PlatformApiCompat {

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

    /**
     * Creates a JCEF load handler that calls the given callback when the main frame finishes loading.
     *
     * <p><b>Why extracted:</b> {@code CefLoadHandlerAdapter} provides default implementations for all
     * {@code CefLoadHandler} methods, but the JCEF version bundled with the dev IDE may declare
     * {@code onLoadError} with a different {@code ErrorCode} enum type than the target platform SDK.
     * In Kotlin, the compiler flags the anonymous subclass as "not implementing abstract member"
     * because of this signature mismatch. In Java, the adapter's default implementation satisfies
     * the contract and no error is reported.</p>
     */
    public static org.cef.handler.CefLoadHandler createMainFrameLoadEndHandler(@NotNull Runnable onMainFrameLoaded) {
        return new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.isMain()) {
                    onMainFrameLoaded.run();
                }
            }
        };
    }

    /**
     * Creates a JCEF display handler that logs console messages to the given logger.
     *
     * <p><b>Why extracted:</b> In newer JCEF versions, {@code LogSeverity} was moved from
     * {@code org.cef.CefSettings.LogSeverity} to a top-level {@code org.cef.LogSeverity} enum.
     * Kotlin's strict override checking flags the old import path as "overrides nothing" because
     * the parameter type doesn't match the parent's signature. In Java, the method resolution
     * handles both paths via the compiled class hierarchy without flagging an error.</p>
     */
    public static org.cef.handler.CefDisplayHandler createConsoleLogHandler(@NotNull Logger logger) {
        return new org.cef.handler.CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(org.cef.browser.CefBrowser browser,
                                            org.cef.CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                logger.info("JCEF Console [" + level + "]: " + message);
                return false;
            }
        };
    }

    /**
     * Subscribes a callback to Look-and-Feel change events on the application message bus.
     *
     * <p><b>Why extracted:</b> {@code LafManagerListener.TOPIC} is typed as
     * {@code Topic<LafManagerListener>} in Java, but Kotlin infers it as a platform type
     * {@code Topic!} which doesn't satisfy the expected generic bound in
     * {@code MessageBusConnection.subscribe()}. This is a Kotlin/Java interop issue with
     * platform types that does not affect runtime behavior.</p>
     */
    public static void subscribeLafChanges(
            @NotNull com.intellij.openapi.Disposable parentDisposable,
            @NotNull Runnable onLafChanged) {
        var conn = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getMessageBus().connect(parentDisposable);
        conn.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC,
                (com.intellij.ide.ui.LafManagerListener) source -> onLafChanged.run());
    }
}
