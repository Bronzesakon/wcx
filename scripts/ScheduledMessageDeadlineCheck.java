package com.Johnny.wcx.features.items.chat;

/** Deterministic clock regression check; needs only a JDK, no Android device or test framework. */
public final class ScheduledMessageDeadlineCheck {
    private static int checks;

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        var timer = new ScheduledMessageDeadline(60_000);
        expect(timer.nextWaitMillis(0, 0) == 1_000, "Long wait must be bounded");
        expect(timer.nextWaitMillis(59_700, 1) == 200, "Leave final precision window");
        expect(timer.nextWaitMillis(59_900, 2) == -1, "Enter final spin");
        expect(timer.nextWaitMillis(60_000, 3) == 0, "Fire at the deadline");

        // Deep sleep advances wall time while JVM monotonic time barely advances.
        var slept = new ScheduledMessageDeadline(60_000);
        expect(slept.nextWaitMillis(0, 0) <= 1_000, "Sleep debt cannot equal the whole timer");
        expect(slept.nextWaitMillis(65_000, 1_000_000) == 0, "Re-read wall clock after sleep");

        // Clock jumps forward, including into the final precision window.
        var forward = new ScheduledMessageDeadline(60_000);
        forward.nextWaitMillis(10_000, 0);
        expect(forward.nextWaitMillis(59_950, 1) == -1, "Forward jump near deadline");
        expect(forward.nextWaitMillis(61_000, 2) == 0, "Forward jump beyond deadline");

        // Clock rolls back during spin: exhaust the budget, yield, and never send early.
        var rollback = new ScheduledMessageDeadline(60_000);
        expect(rollback.nextWaitMillis(59_950, 0) == -1, "Start rollback case spin");
        expect(rollback.nextWaitMillis(59_925, 150_000_000) == 1, "Yield after spin budget");
        expect(rollback.nextWaitMillis(50_000, 151_000_000) == 1_000, "Resume bounded waiting after rollback");
        expect(rollback.nextWaitMillis(59_999, 152_000_000) != 0, "Never fire before wall deadline");
        expect(rollback.nextWaitMillis(60_000, 153_000_000) == 0, "Eventually fire after rollback");

        // Simulate a complete timer, with a long sleep midway and only 1 ms monotonic progress.
        long wall = 0;
        long monotonic = 0;
        var simulation = new ScheduledMessageDeadline(600_000);
        int turns = 0;
        while (true) {
            long wait = simulation.nextWaitMillis(wall, monotonic);
            if (wait == 0) break;
            expect(wait > 0 && wait <= 1_000, "Every coarse wait remains bounded");
            wall += wait;
            monotonic += wait * 1_000_000;
            if (++turns == 5) {
                wall += 600_000;
                monotonic += 1_000_000;
            }
            expect(turns <= 5, "Do not keep waiting on pre-sleep relative deadline");
        }
        expect(wall >= 600_000 && turns == 5, "Sleep recovery uses wall time immediately");
        System.out.println("PASS: " + checks + " wall-clock deadline checks");
    }
}
