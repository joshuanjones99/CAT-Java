package com.verifone.cat.sims;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link WindowOps}: the windows each PID shows, optionally only after a number of looks,
 * and a record of every minimise. Touches no real window.
 */
final class FakeWindowOps implements WindowOps {

    final Map<Long, List<TopWindow>> windows = new ConcurrentHashMap<>();
    /** How many lookups answer nothing before the windows appear. */
    volatile int hiddenFor;
    /** When set, every lookup answers with the windows of this one, whatever PID is asked. */
    volatile List<TopWindow> anyPid;
    final AtomicInteger lookups = new AtomicInteger();
    final List<Long> minimised = new CopyOnWriteArrayList<>();

    @Override
    public List<TopWindow> topLevelWindows(long pid) {
        if (lookups.incrementAndGet() <= hiddenFor) {
            return List.of();
        }
        if (anyPid != null) {
            return anyPid;
        }
        return windows.getOrDefault(pid, List.of());
    }

    @Override
    public void minimise(long hwnd) {
        minimised.add(hwnd);
    }
}
