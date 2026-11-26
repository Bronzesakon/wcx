package com.Johnny.wcx.features.items.chat;

import java.util.Arrays;

/** Small bounded, thread-safe timing distribution used by the scheduler probes. */
public final class ScheduledMessageTimingStats {
    private final long[] samples;
    private int next;
    private int count;

    public ScheduledMessageTimingStats(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        samples = new long[capacity];
    }

    public synchronized void add(long valueNs) {
        if (valueNs < 0) throw new IllegalArgumentException("timing must be non-negative");
        samples[next] = valueNs;
        next = (next + 1) % samples.length;
        if (count < samples.length) count++;
    }

    public synchronized void reset() {
        next = 0;
        count = 0;
        Arrays.fill(samples, 0L);
    }

    public synchronized int size() {
        return count;
    }

    public synchronized long percentile(double percentile) {
        if (count == 0) return 0;
        if (percentile < 0 || percentile > 100) throw new IllegalArgumentException("percentile out of range");
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        if (count == 1) return copy[0];
        double rank = (percentile / 100.0) * (count - 1);
        int index = (int) Math.ceil(rank);
        return copy[Math.min(count - 1, Math.max(0, index))];
    }

    public synchronized long max() {
        long max = 0;
        for (int i = 0; i < count; i++) max = Math.max(max, samples[i]);
        return max;
    }
}
