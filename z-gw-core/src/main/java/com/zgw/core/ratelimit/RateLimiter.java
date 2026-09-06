package com.zgw.core.ratelimit;

import java.time.Duration;

/**
 * Rate limiter interface
 */
public interface RateLimiter {

    /**
     * Check if request is allowed
     *
     * @param key rate limit key (e.g., IP, user ID, API key)
     * @return true if allowed, false if rate limited
     */
    boolean isAllowed(String key);

    /**
     * Check if request is allowed with specific permits
     *
     * @param key     rate limit key
     * @param permits number of permits to acquire
     * @return true if allowed
     */
    boolean isAllowed(String key, int permits);

    /**
     * Get current rate limit status
     *
     * @param key rate limit key
     * @return rate limit status
     */
    RateLimitStatus getStatus(String key);

    /**
     * Get rate limiter type
     */
    String getType();

    /**
     * Rate limit status
     */
    class RateLimitStatus {
        private final boolean allowed;
        private final long remaining;
        private final long limit;
        private final long resetTime;
        private final long retryAfter;

        public RateLimitStatus(boolean allowed, long remaining, long limit, long resetTime) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.limit = limit;
            this.resetTime = resetTime;
            this.retryAfter = allowed ? 0 : Math.max(0, resetTime - System.currentTimeMillis());
        }

        // Getters
        public boolean isAllowed() {
            return allowed;
        }

        public long getRemaining() {
            return remaining;
        }

        public long getLimit() {
            return limit;
        }

        public long getResetTime() {
            return resetTime;
        }

        public long getRetryAfter() {
            return retryAfter;
        }

        @Override
        public String toString() {
            return "RateLimitStatus{" +
                    "allowed=" + allowed +
                    ", remaining=" + remaining +
                    ", limit=" + limit +
                    ", retryAfter=" + retryAfter +
                    '}';
        }
    }

    /**
     * Rate limit configuration
     */
    class RateLimitConfig {
        private final long limit;
        private final Duration window;
        private final int burstSize;

        public RateLimitConfig(long limit, Duration window) {
            this(limit, window, (int) limit);
        }

        public RateLimitConfig(long limit, Duration window, int burstSize) {
            this.limit = limit;
            this.window = window;
            this.burstSize = burstSize;
        }

        // Getters
        public long getLimit() {
            return limit;
        }

        public Duration getWindow() {
            return window;
        }

        public int getBurstSize() {
            return burstSize;
        }

        public long getWindowInMillis() {
            return window.toMillis();
        }
    }
}