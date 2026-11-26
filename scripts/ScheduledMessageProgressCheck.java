package com.Johnny.wcx.features.items.chat;

import java.util.List;
import java.util.Map;

/** Deterministic checks for per-target segment progress; no Android device required. */
public final class ScheduledMessageProgressCheck {
    private static int checks;

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        Map<String, List<Integer>> progress = Map.of();
        expect(!ScheduledMessageProgress.isSegmentSent(progress, "a", 0), "Empty progress sends nothing");
        expect(ScheduledMessageProgress.firstUnsentSegment(progress, "a", 3) == 0, "First segment is pending");

        progress = ScheduledMessageProgress.markSegmentSent(progress, "a", 0);
        expect(ScheduledMessageProgress.isSegmentSent(progress, "a", 0), "Successful segment is remembered");
        expect(ScheduledMessageProgress.firstUnsentSegment(progress, "a", 3) == 1, "Retry resumes after success");
        expect(!ScheduledMessageProgress.isSegmentSent(progress, "b", 0), "Targets are isolated");

        progress = ScheduledMessageProgress.markSegmentSent(progress, "a", 2);
        expect(ScheduledMessageProgress.firstUnsentSegment(progress, "a", 3) == 1, "Out-of-order success is supported");
        progress = ScheduledMessageProgress.markSegmentSent(progress, "a", 1);
        expect(ScheduledMessageProgress.isTargetComplete(progress, "a", 3), "All segments complete the target");
        expect(!ScheduledMessageProgress.isTargetComplete(progress, "b", 3), "Other target remains pending");

        Map<String, List<Integer>> snapshot = Map.of("a", List.of(0));
        Map<String, List<Integer>> updated = ScheduledMessageProgress.markSegmentSent(snapshot, "a", 0);
        expect(updated.get("a").size() == 1, "Duplicate success is idempotent");
        expect(snapshot.get("a").size() == 1, "Input map is not mutated");

        boolean rejected = false;
        try {
            ScheduledMessageProgress.markSegmentSent(progress, "a", -1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        expect(rejected, "Invalid segment index is rejected");

        System.out.println("PASS: " + checks + " scheduled-message progress checks");
    }
}
