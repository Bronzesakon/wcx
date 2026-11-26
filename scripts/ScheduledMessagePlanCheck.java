package com.Johnny.wcx.features.items.chat;

/** Deterministic retry/day-boundary checks; no Android device required. */
public final class ScheduledMessagePlanCheck {
    private static int checks;

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        String today = "2026-11-26";
        expect(ScheduledMessagePlan.effectiveFireAttempts("", 3, today) == 0, "Missing date starts a fresh day");
        expect(ScheduledMessagePlan.effectiveFireAttempts("2026-11-25", 3, today) == 0, "Cross-day attempts reset");
        expect(ScheduledMessagePlan.effectiveFireAttempts(today, 2, today) == 2, "Same-day attempts are retained");
        expect(ScheduledMessagePlan.canFire(today, 2, today, 3), "Attempts below the limit are allowed");
        expect(!ScheduledMessagePlan.canFire(today, 3, today, 3), "Attempt limit is enforced");
        expect(ScheduledMessagePlan.canFire("2026-11-25", 3, today, 3), "New day restores the attempt budget");
        expect(ScheduledMessagePlan.alreadyFiredToday(today, today), "Same-day success suppresses duplicates");
        expect(!ScheduledMessagePlan.alreadyFiredToday("", today), "No success does not suppress sending");

        expect(ScheduledMessagePlan.isWithinCatchupWindow(10_000, 9_000, 1_000), "Catch-up boundary is inclusive");
        expect(!ScheduledMessagePlan.isWithinCatchupWindow(11_001, 9_000, 1_000), "Expired catch-up is rejected");
        expect(!ScheduledMessagePlan.isWithinCatchupWindow(9_000, 10_000, 1_000), "Future schedule is not a catch-up");
        expect(!ScheduledMessagePlan.isWithinCatchupWindow(10_000, 0, 1_000), "Never-scheduled task is not a catch-up");

        expect(ScheduledMessagePlan.retryAt(10_000, true, 2_000, 30_000) == 12_000, "Daily retry delay");
        expect(ScheduledMessagePlan.retryAt(10_000, false, 2_000, 30_000) == 40_000, "One-time retry delay");
        expect(ScheduledMessagePlan.retryAt(10_000, false, 2_000, -1) == 10_000, "Negative delay is clamped");

        System.out.println("PASS: " + checks + " scheduled-message plan checks");
    }
}
