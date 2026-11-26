package com.Johnny.wcx.features.items.chat;

import java.util.Objects;

/** Pure retry/day-boundary helpers for scheduled-message decisions. */
public final class ScheduledMessagePlan {
    private ScheduledMessagePlan() {}

    public static int effectiveFireAttempts(String attemptDate, int fireAttempts, String today) {
        if (!Objects.equals(attemptDate, today)) return 0;
        return Math.max(0, fireAttempts);
    }

    public static boolean canFire(String attemptDate, int fireAttempts, String today, int maxAttempts) {
        return maxAttempts > 0 && effectiveFireAttempts(attemptDate, fireAttempts, today) < maxAttempts;
    }

    public static boolean alreadyFiredToday(String lastFiredDate, String today) {
        return Objects.equals(lastFiredDate, today);
    }

    public static boolean isWithinCatchupWindow(long nowMs, long nextSendTimeMs, long windowMs) {
        return nextSendTimeMs > 0 && nextSendTimeMs <= nowMs && nowMs - nextSendTimeMs <= windowMs;
    }

    public static long retryAt(long nowMs, boolean repeatDaily, long dailyDelayMs, long oneTimeDelayMs) {
        long delay = repeatDaily ? dailyDelayMs : oneTimeDelayMs;
        return nowMs + Math.max(0, delay);
    }
}
