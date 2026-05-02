package com.github.catatafishen.agentbridge.ui.statistics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchComparisonSorterTest {

    @Test
    void sortByBranchNameUsesCaseInsensitiveOrder() {
        List<UsageStatisticsData.BranchStats> sorted = BranchComparisonSorter.sort(
            List.of(
                branch("feat/z", LocalDate.of(2025, 1, 1), 1, 1.0),
                branch("Feat/a", LocalDate.of(2025, 1, 1), 1, 1.0),
                branch("feat/b", LocalDate.of(2025, 1, 1), 1, 1.0)
            ),
            UsageStatisticsData.BranchSort.BRANCH_NAME,
            UsageStatisticsData.Metric.PREMIUM_REQUESTS
        );

        assertEquals(List.of("Feat/a", "feat/b", "feat/z"), sorted.stream()
            .map(UsageStatisticsData.BranchStats::branch)
            .toList());
    }

    @Test
    void sortByFirstDetectedUsesOldestDateThenBranchName() {
        List<UsageStatisticsData.BranchStats> sorted = BranchComparisonSorter.sort(
            List.of(
                branch("feat/new", LocalDate.of(2025, 2, 1), 1, 1.0),
                branch("feat/b", LocalDate.of(2025, 1, 1), 1, 1.0),
                branch("feat/a", LocalDate.of(2025, 1, 1), 1, 1.0)
            ),
            UsageStatisticsData.BranchSort.FIRST_DETECTED,
            UsageStatisticsData.Metric.PREMIUM_REQUESTS
        );

        assertEquals(List.of("feat/a", "feat/b", "feat/new"), sorted.stream()
            .map(UsageStatisticsData.BranchStats::branch)
            .toList());
    }

    @Test
    void sortByBarValueUsesSelectedMetricDescending() {
        List<UsageStatisticsData.BranchStats> sorted = BranchComparisonSorter.sort(
            List.of(
                branch("feat/turns", LocalDate.of(2025, 1, 1), 10, 0.1),
                branch("feat/premium", LocalDate.of(2025, 1, 1), 2, 9.0),
                branch("feat/middle", LocalDate.of(2025, 1, 1), 5, 1.0)
            ),
            UsageStatisticsData.BranchSort.BAR_VALUE,
            UsageStatisticsData.Metric.TURNS
        );

        assertEquals(List.of("feat/turns", "feat/middle", "feat/premium"), sorted.stream()
            .map(UsageStatisticsData.BranchStats::branch)
            .toList());
    }

    private static UsageStatisticsData.BranchStats branch(
        String name,
        LocalDate firstDetected,
        int turns,
        double premiumRequests
    ) {
        return new UsageStatisticsData.BranchStats(
            name,
            firstDetected,
            turns,
            100,
            200,
            3,
            5_000,
            10,
            2,
            premiumRequests
        );
    }
}
