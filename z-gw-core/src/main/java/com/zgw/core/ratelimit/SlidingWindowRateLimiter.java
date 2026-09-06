package com.zgw.core.ratelimit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding Window Rate Limiter
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger logger = LogManager.getLogger(SlidingWindowRateLimiter.class);

    private final RateLimitConfig config;
    private final int windowCount; // Number of sub-windows
    private final long subWindowMillis;
    private final ConcurrentHashMap<String, SlidingWindow> windows;

    public SlidingWindowRateLimiter(long limit, Duration window) {
        this(new RateLimitConfig(limit, window));
    }

    public SlidingWindowRateLimiter(RateLimitConfig config) {
        this(config, 10); // Default 10 sub-windows
    }

    public SlidingWindowRateLimiter(RateLimitConfig config, int windowCount) {
        this.config = config;
        this.windowCount = windowCount;
        this.subWindowMillis = config.getWindowInMillis() / windowCount;
        this.windows = new ConcurrentHashMap<>();

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

        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow(config, windowCount));
        return window.tryAcquire(permits);
    }

    @Override
    public RateLimitStatus getStatus(String key) {
        SlidingWindow window = windows.get(key);
        if (window == null) {
            return new RateLimitStatus(true, config.getLimit(), config.getLimit(),
                    System.currentTimeMillis() + config.getWindowInMillis());
        }

        return window.getStatus();
    }

    @Override
    public String getType() {
        return "slidingWindow";
    }

    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MINUTES.sleep(1);
                    cleanupExpiredWindows();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        cleanupThread.setName("sliding-window-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private void cleanupExpiredWindows() {
        long now = System.currentTimeMillis();
        long windowMillis = config.getWindowInMillis() * 2;

        windows.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().getLastAccessTime() > windowMillis;
            if (expired && logger.isDebugEnabled()) {
                logger.debug("Removed expired window for key: {}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * Sliding window implementation
     */
    private static class SlidingWindow {
        private final long limit;
        private final long windowMillis;
        private final int windowCount;
        private final long subWindowMillis;

        // Circular buffer for sub-windows
        private final AtomicLong[] subWindowCounts;
        private final AtomicLong[] subWindowStarts;

        private final AtomicLong currentCount;
        private volatile long lastAccessTime;

        SlidingWindow(RateLimitConfig config, int windowCount) {
            this.limit = config.getLimit();
            this.windowMillis = config.getWindowInMillis();
            this.windowCount = windowCount;
            this.subWindowMillis = windowMillis / windowCount;

            this.subWindowCounts = new AtomicLong[windowCount];
            this.subWindowStarts = new AtomicLong[windowCount];
            for (int i = 0; i < windowCount; i++) {
                subWindowCounts[i] = new AtomicLong(0);
                subWindowStarts[i] = new AtomicLong(0);
            }

            this.currentCount = new AtomicLong(0);
            this.lastAccessTime = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int permits) {
            long now = System.currentTimeMillis();
            lastAccessTime = now;

            // Calculate current window count
            long currentWindowCount = calculateCurrentWindowCount(now);

            // Check if request can be allowed
            if (currentWindowCount + permits <= limit) {
                // Update sub-window count
                int subWindowIndex = (int) ((now % windowMillis) / subWindowMillis);

                // Check if we need to reset this sub-window
                long subWindowStart = subWindowStarts[subWindowIndex].get();
                if (now - subWindowStart >= subWindowMillis) {
                    subWindowCounts[subWindowIndex].set(0);
                    subWindowStarts[subWindowIndex].set(now);
                }

                subWindowCounts[subWindowIndex].addAndGet(permits);
                currentCount.set(currentWindowCount + permits);

                return true;
            }

            return false;
        }

        private long calculateCurrentWindowCount(long now) {
            long count = 0;
            long windowStart = now - windowMillis;

            for (int i = 0; i < windowCount; i++) {
                long subWindowStart = subWindowStarts[i].get();
                if (subWindowStart > windowStart && subWindowStart <= now) {
                    count += subWindowCounts[i].get();
                }
            }

            return count;
        }

        RateLimitStatus getStatus() {
            long now = System.currentTimeMillis();
            long currentWindowCount = calculateCurrentWindowCount(now);
            long remaining = Math.max(0, limit - currentWindowCount);

            // Estimate reset time based on oldest sub-window
            long oldestSubWindow = now;
            for (int i = 0; i < windowCount; i++) {
                long subWindowStart = subWindowStarts[i].get();
                if (subWindowStart > 0 && subWindowStart < oldestSubWindow) {
                    oldestSubWindow = subWindowStart;
                }
            }
            long resetTime = oldestSubWindow + windowMillis;

            return new RateLimitStatus(remaining > 0, remaining, limit, resetTime);
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }
    }
}