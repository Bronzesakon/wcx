package com.Johnny.wcx.features.items.chat;

/** Deterministic checks for bounded timing statistics. */
public final class ScheduledMessageTimingStatsCheck {
    private static int checks;

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        ScheduledMessageTimingStats stats = new ScheduledMessageTimingStats(4);
        expect(stats.size() == 0, "New stats are empty");
        expect(stats.percentile(95) == 0 && stats.max() == 0, "Empty stats are zero");

        stats.add(10);
        stats.add(20);
        stats.add(30);
        stats.add(40);
        expect(stats.percentile(50) == 30, "Median uses nearest upper rank");
        expect(stats.percentile(95) == 40, "p95 sees the maximum");
        expect(stats.max() == 40, "Max is tracked");

        stats.add(50);
        expect(stats.size() == 4, "Capacity is bounded");
        expect(stats.percentile(50) == 40, "Oldest sample is evicted");
        expect(stats.max() == 50, "Max reflects retained samples");

        stats.reset();
        expect(stats.size() == 0 && stats.max() == 0, "Reset clears retained samples");

        boolean rejected = false;
        try {
            stats.add(-1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        expect(rejected, "Negative timing is rejected");

        System.out.println("PASS: " + checks + " scheduled-message timing-stat checks");
    }
}
