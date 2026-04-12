package com.github.catatafishen.agentbridge.ui.statistics;

import com.intellij.ui.scale.JBUIScale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for private static helper methods in {@link UsageStatisticsChart}
 * accessed via reflection.
 */
class UsageStatisticsChartTest {

    /**
     * Pre-initialize JBUI scale factors so that the static initializer in
     * {@link UsageStatisticsChart} (which calls {@code JBUI.scale()}) does not
     * fail in a headless test environment.
     */
    @BeforeAll
    static void initJBUIScale() {
        JBUIScale.setSystemScaleFactor(1.0f);
        JBUIScale.setUserScaleFactorForTest(1.0f);
    }

    // ── reflection helpers ──────────────────────────────────────────────

    private static String invokeFormatCompact(long value) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod("formatCompact", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    private static String invokeFormatDuration(long minutes) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod("formatDuration", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, minutes);
    }

    private static String invokeFormatYLabel(long value, UsageStatisticsData.Metric metric) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod(
            "formatYLabel", long.class, UsageStatisticsData.Metric.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value, metric);
    }

    private static List<?> invokeFillDateRange(Map<LocalDate, Long> valuesByDate,
                                               LocalDate start, LocalDate end) throws Exception {
        Method m = UsageStatisticsChart.class.getDeclaredMethod(
            "fillDateRange", Map.class, LocalDate.class, LocalDate.class);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, valuesByDate, start, end);
    }

    /**
     * Extract the {@code y} field from a private {@code DataPoint} record via reflection.
     */
    private static long dataPointY(Object dataPoint) throws Exception {
        Field f = dataPoint.getClass().getDeclaredField("y");
        f.setAccessible(true);
        return f.getLong(dataPoint);
    }

    /**
     * Extract the {@code date} field from a private {@code DataPoint} record via reflection.
     */
    private static LocalDate dataPointDate(Object dataPoint) throws Exception {
        Field f = dataPoint.getClass().getDeclaredField("date");
        f.setAccessible(true);
        return (LocalDate) f.get(dataPoint);
    }

    // ── formatCompact tests ─────────────────────────────────────────────

    @Test
    void formatCompact_zero() throws Exception {
        assertEquals("0", invokeFormatCompact(0));
    }

    @Test
    void formatCompact_smallValue() throws Exception {
        assertEquals("42", invokeFormatCompact(42));
    }

    @Test
    void formatCompact_justBelowThousand() throws Exception {
        assertEquals("999", invokeFormatCompact(999));
    }

    @Test
    void formatCompact_exactlyOneThousand() throws Exception {
        assertEquals("1K", invokeFormatCompact(1000));
    }

    @Test
    void formatCompact_fractionalThousand() throws Exception {
        assertEquals("1.5K", invokeFormatCompact(1500));
    }

    @Test
    void formatCompact_exactlyTwoThousand() throws Exception {
        assertEquals("2K", invokeFormatCompact(2000));
    }

    @Test
    void formatCompact_exactlyOneMillion() throws Exception {
        assertEquals("1M", invokeFormatCompact(1_000_000));
    }

    @Test
    void formatCompact_fractionalMillion() throws Exception {
        assertEquals("1.5M", invokeFormatCompact(1_500_000));
    }

    @Test
    void formatCompact_negativeValue() throws Exception {
        assertEquals("-1.5K", invokeFormatCompact(-1500));
    }

    @Test
    void formatCompact_largeThousand() throws Exception {
        // 999_999 → 1000.0K which is not an integer, so String.format("%.1fK", 1000.0) → "1000.0K"
        // Actually 999_999 / 1000.0 = 999.999 → not == 999 → "1000.0K"
        String result = invokeFormatCompact(999_999);
        assertTrue(result.endsWith("K"), "Expected a K suffix for 999999, got: " + result);
    }

    // ── formatDuration tests ────────────────────────────────────────────

    @Test
    void formatDuration_zero() throws Exception {
        assertEquals("0m", invokeFormatDuration(0));
    }

    @Test
    void formatDuration_singleMinute() throws Exception {
        assertEquals("1m", invokeFormatDuration(1));
    }

    @Test
    void formatDuration_underOneHour() throws Exception {
        assertEquals("45m", invokeFormatDuration(45));
    }

    @Test
    void formatDuration_exactlyOneHour() throws Exception {
        assertEquals("1h", invokeFormatDuration(60));
    }

    @Test
    void formatDuration_hourAndMinutes() throws Exception {
        assertEquals("1h 30m", invokeFormatDuration(90));
    }

    @Test
    void formatDuration_exactlyTwoHours() throws Exception {
        assertEquals("2h", invokeFormatDuration(120));
    }

    // ── formatYLabel tests ──────────────────────────────────────────────

    @Test
    void formatYLabel_agentTimeMetricDelegatesToDuration() throws Exception {
        assertEquals("1h 30m", invokeFormatYLabel(90, UsageStatisticsData.Metric.AGENT_TIME));
    }

    @Test
    void formatYLabel_nonAgentTimeMetricDelegatesToCompact() throws Exception {
        assertEquals("1.5K", invokeFormatYLabel(1500, UsageStatisticsData.Metric.TOKENS));
    }

    @Test
    void formatYLabel_turnsMetricDelegatesToCompact() throws Exception {
        assertEquals("42", invokeFormatYLabel(42, UsageStatisticsData.Metric.TURNS));
    }

    // ── fillDateRange tests ─────────────────────────────────────────────

    @Test
    void fillDateRange_emptyMap_allZeros() throws Exception {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 3);

        List<?> points = invokeFillDateRange(Map.of(), start, end);

        assertEquals(3, points.size());
        for (Object pt : points) {
            assertEquals(0L, dataPointY(pt));
        }
    }

    @Test
    void fillDateRange_gapsFilledWithZeros() throws Exception {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 5);

        Map<LocalDate, Long> values = new LinkedHashMap<>();
        values.put(LocalDate.of(2025, 1, 1), 10L);
        values.put(LocalDate.of(2025, 1, 3), 30L);
        values.put(LocalDate.of(2025, 1, 5), 50L);

        List<?> points = invokeFillDateRange(values, start, end);

        assertEquals(5, points.size());
        assertEquals(10L, dataPointY(points.getFirst()));
        assertEquals(0L, dataPointY(points.get(1)));  // Jan 2 – gap
        assertEquals(30L, dataPointY(points.get(2)));
        assertEquals(0L, dataPointY(points.get(3)));  // Jan 4 – gap
        assertEquals(50L, dataPointY(points.get(4)));
    }

    @Test
    void fillDateRange_singleDay() throws Exception {
        LocalDate day = LocalDate.of(2025, 6, 15);
        Map<LocalDate, Long> values = Map.of(day, 99L);

        List<?> points = invokeFillDateRange(values, day, day);

        assertEquals(1, points.size());
        assertEquals(99L, dataPointY(points.getFirst()));
        assertEquals(day, dataPointDate(points.getFirst()));
    }

    @Test
    void fillDateRange_startEqualsEnd_noData() throws Exception {
        LocalDate day = LocalDate.of(2025, 3, 10);

        List<?> points = invokeFillDateRange(Map.of(), day, day);

        assertEquals(1, points.size());
        assertEquals(0L, dataPointY(points.getFirst()));
    }

    @Test
    void fillDateRange_datesAreInOrder() throws Exception {
        LocalDate start = LocalDate.of(2025, 2, 1);
        LocalDate end = LocalDate.of(2025, 2, 4);

        List<?> points = invokeFillDateRange(Map.of(), start, end);

        assertEquals(4, points.size());
        assertEquals(LocalDate.of(2025, 2, 1), dataPointDate(points.get(0)));
        assertEquals(LocalDate.of(2025, 2, 2), dataPointDate(points.get(1)));
        assertEquals(LocalDate.of(2025, 2, 3), dataPointDate(points.get(2)));
        assertEquals(LocalDate.of(2025, 2, 4), dataPointDate(points.get(3)));
    }
}
