package com.zgw.core.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed Window Rate Limiter
 */
public class FixedWindowRateLimiter implements RateLimiter {

    private final RateLimitConfig config;
    private final ConcurrentHashMap<String, FixedWindow> windows;

    public FixedWindowRateLimiter(long limit, Duration window) {
        this(new RateLimitConfig(limit, window));
    }

    public FixedWindowRateLimiter(RateLimitConfig config) {
        this.config = config;
        this.windows = new ConcurrentHashMap<>();

        // Start cleanup thread
        startCleanupThread();
    }

    @Override
    public boolean isAllowed(String key) {
        return isAllowed(key, 1);
    }

    @Override
    public boolean isAllowed(String key, int permits) {
        if (key == null) {
            return true;
        }

        FixedWindow window = windows.computeIfAbsent(key, k -> new FixedWindow(config));
        return window.tryAcquire(permits);
    }

    @Override
    public RateLimitStatus getStatus(String key) {
        FixedWindow window = windows.get(key);
        if (window == null) {
            return new RateLimitStatus(true, config.getLimit(), config.getLimit(),
                    System.currentTimeMillis() + config.getWindowInMillis());
        }

        return window.getStatus();
    }

    @Override
    public String getType() {
        return "fixedWindow";
    }

    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MINUTES.sleep(5);
                    cleanupExpiredWindows();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        cleanupThread.setName("fixed-window-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private void cleanupExpiredWindows() {
        long now = System.currentTimeMillis();
        long expireTime = config.getWindowInMillis() * 2;

        windows.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().getWindowStart() > expireTime;
            if (expired) {
                System.out.println("Cleaned up expired window for key: " + entry.getKey());
            }
            return expired;
        });
    }

    /**
     * Fixed window implementation
     */
    private static class FixedWindow {
        private final long limit;
        private final long windowMillis;
        private final AtomicInteger count;
        private volatile long windowStart;

        FixedWindow(RateLimitConfig config) {
            this.limit = config.getLimit();
            this.windowMillis = config.getWindowInMillis();
            this.windowStart = System.currentTimeMillis();
            this.count = new AtomicInteger(0);
        }

        synchronized boolean tryAcquire(int permits) {
            long now = System.currentTimeMillis();

            // Check if window has expired
            if (now - windowStart >= windowMillis) {
                windowStart = now;
                count.set(0);
            }

            // Check if request can be allowed
            if (count.get() + permits <= limit) {
                count.addAndGet(permits);
                return true;
            }

            return false;
        }

        RateLimitStatus getStatus() {
            long now = System.currentTimeMillis();
            long currentCount = count.get();
            long remaining = Math.max(0, limit - currentCount);
            long resetTime = windowStart + windowMillis;

            return new RateLimitStatus(remaining > 0, remaining, limit, resetTime);
        }

        long getWindowStart() {
            return windowStart;
        }
    }
}