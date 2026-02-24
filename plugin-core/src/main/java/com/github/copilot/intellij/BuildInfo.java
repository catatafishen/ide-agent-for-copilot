package com.github.copilot.intellij;

import java.io.InputStream;
import java.util.Properties;

/**
 * Reads build metadata generated at compile time.
 */
public final class BuildInfo {
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = BuildInfo.class.getClassLoader()
                .getResourceAsStream("build-info.properties")) {
            if (is != null) PROPS.load(is);
        } catch (Exception ignored) {
        }
    }

    public static String getTimestamp() {
        String raw = PROPS.getProperty("build.timestamp", "unknown");
        try {
            long millis = Long.parseLong(raw);
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(millis));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    public static String getGitHash() {
        return PROPS.getProperty("build.git.hash", "unknown");
    }

    public static String getVersion() {
        return PROPS.getProperty("build.version", "unknown");
    }

    public static String getSummary() {
        return getVersion() + " (" + getGitHash() + " @ " + getTimestamp() + ")";
    }

    private BuildInfo() {
    }
}
