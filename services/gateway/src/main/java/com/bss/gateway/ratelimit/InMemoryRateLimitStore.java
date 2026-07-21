package com.bss.gateway.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed windows in a map — exact for ONE gateway, forgotten on restart. */
public class InMemoryRateLimitStore implements RateLimitStore {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    record Window(long startedAt, int count) {
    }

    @Override
    public synchronized long tryAcquire(String key, int capacity, long windowMs) {
        long now = System.currentTimeMillis();
        Window window = windows.get(key);
        if (window == null || now - window.startedAt() >= windowMs) {
            windows.put(key, new Window(now, 1));
            return 0;
        }
        if (window.count() < capacity) {
            windows.put(key, new Window(window.startedAt(), window.count() + 1));
            return 0;
        }
        return Math.max(1, (window.startedAt() + windowMs - now + 999) / 1000);
    }
}
