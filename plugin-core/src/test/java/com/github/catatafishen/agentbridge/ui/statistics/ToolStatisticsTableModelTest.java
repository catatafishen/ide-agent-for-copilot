package com.github.catatafishen.agentbridge.ui.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ToolStatisticsTableModel} — column rendering, byte formatting, error rate.
 */
@DisplayName("ToolStatisticsTableModel – formatBytes")
class ToolStatisticsTableModelTest {

    @Test
    @DisplayName("0 bytes → '0 B'")
    void formatBytes_zero() {
        assertEquals("0 B", ToolStatisticsTableModel.formatBytes(0));
    }

    @Test
    @DisplayName("512 bytes → '512 B'")
    void formatBytes_512() {
        assertEquals("512 B", ToolStatisticsTableModel.formatBytes(512));
    }

    @Test
    @DisplayName("1023 bytes → '1023 B'")
    void formatBytes_1023() {
        assertEquals("1023 B", ToolStatisticsTableModel.formatBytes(1023));
    }

    @Test
    @DisplayName("1024 bytes → '1.0 KB'")
    void formatBytes_1024() {
        assertEquals("1.0 KB", ToolStatisticsTableModel.formatBytes(1024));
    }

    @Test
    @DisplayName("1536 bytes → '1.5 KB'")
    void formatBytes_1536() {
        assertEquals("1.5 KB", ToolStatisticsTableModel.formatBytes(1536));
    }

    @Test
    @DisplayName("1048576 bytes → '1.0 MB'")
    void formatBytes_oneMegabyte() {
        assertEquals("1.0 MB", ToolStatisticsTableModel.formatBytes(1_048_576));
    }

    @Test
    @DisplayName("1572864 bytes → '1.5 MB'")
    void formatBytes_oneAndHalfMegabytes() {
        assertEquals("1.5 MB", ToolStatisticsTableModel.formatBytes(1_572_864));
    }
}
