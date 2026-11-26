package com.Johnny.wcx.features.items.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure per-target segment progress helpers, shared by the scheduler and its JVM checks. */
public final class ScheduledMessageProgress {
    private ScheduledMessageProgress() {}

    public static boolean isSegmentSent(
            Map<String, ? extends Collection<Integer>> progress,
            String target,
            int segmentIndex
    ) {
        if (progress == null || target == null || segmentIndex < 0) return false;
        Collection<Integer> sent = progress.get(target);
        return sent != null && sent.contains(segmentIndex);
    }

    /** Returns a copy; callers persist the returned map under their storage lock. */
    public static Map<String, List<Integer>> markSegmentSent(
            Map<String, List<Integer>> progress,
            String target,
            int segmentIndex
    ) {
        if (target == null || segmentIndex < 0) {
            throw new IllegalArgumentException("target and segmentIndex must be valid");
        }
        LinkedHashMap<String, List<Integer>> copy = new LinkedHashMap<>();
        if (progress != null) copy.putAll(progress);
        List<Integer> sent = copy.get(target);
        ArrayList<Integer> next = sent == null ? new ArrayList<>() : new ArrayList<>(sent);
        if (!next.contains(segmentIndex)) next.add(segmentIndex);
        Collections.sort(next);
        copy.put(target, next);
        return copy;
    }

    public static int firstUnsentSegment(
            Map<String, ? extends Collection<Integer>> progress,
            String target,
            int segmentCount
    ) {
        for (int i = 0; i < segmentCount; i++) {
            if (!isSegmentSent(progress, target, i)) return i;
        }
        return -1;
    }

    public static boolean isTargetComplete(
            Map<String, ? extends Collection<Integer>> progress,
            String target,
            int segmentCount
    ) {
        return firstUnsentSegment(progress, target, segmentCount) < 0;
    }
}
