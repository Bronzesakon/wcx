package com.Johnny.wcx.features.items.chat;

/** Wall-clock deadline, with bounded sleeps and a bounded final spin. */
final class ScheduledMessageDeadline {
    static final long RECHECK_MS = 1_000L;
    static final long SPIN_LEAD_MS = 100L;
    private static final long MAX_SPIN_NS = 150_000_000L;

    private final long deadlineMs;
    private boolean spinning;
    private long spinStartedNs;

    ScheduledMessageDeadline(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    /** 0 means due, -1 means spin, and a positive value is the next cancellable sleep. */
    long nextWaitMillis(long wallNowMs, long monotonicNowNs) {
        long remainingMs = deadlineMs - wallNowMs;
        if (remainingMs <= 0) return 0;
        if (remainingMs > SPIN_LEAD_MS) {
            spinning = false;
            // A relative JVM timer excludes deep sleep. Re-read wall time after every chunk.
            return Math.min(remainingMs - SPIN_LEAD_MS, RECHECK_MS);
        }
        if (!spinning) {
            spinning = true;
            spinStartedNs = monotonicNowNs;
        }
        if (monotonicNowNs - spinStartedNs >= MAX_SPIN_NS) {
            spinning = false;
            // A wall-clock rollback must never turn the spin limit into an early send.
            return 1;
        }
        return -1;
    }
}
