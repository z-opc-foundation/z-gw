package com.zgw.core.ratelimit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Token Bucket Rate Limiter
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger logger = LogManager.getLogger(TokenBucketRateLimiter.class);

    private final RateLimitConfig config;
    private final ConcurrentHashMap<String, TokenBucket> buckets;

    public TokenBucketRateLimiter(long limit, Duration window) {
        this(new RateLimitConfig(limit, window));
    }

    public TokenBucketRateLimiter(RateLimitConfig config) {
        this.config = config;
        this.buckets = new ConcurrentHashMap<>();

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

        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(config));
        return bucket.tryAcquire(permits);
    }

    @Override
    public RateLimitStatus getStatus(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) {
            return new RateLimitStatus(true, config.getLimit(), config.getLimit(),
                    System.currentTimeMillis() + config.getWindowInMillis());
        }

        return bucket.getStatus();
    }

    @Override
    public String getType() {
        return "tokenBucket";
    }

    /**
     * Start cleanup thread for expired buckets
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MINUTES.sleep(1);
                    cleanupExpiredBuckets();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        cleanupThread.setName("token-bucket-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Remove expired buckets
     */
    private void cleanupExpiredBuckets() {
        long now = System.currentTimeMillis();
        long windowMillis = config.getWindowInMillis() * 2; // 2 windows grace period

        buckets.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().getLastAccessTime() > windowMillis;
            if (expired && logger.isDebugEnabled()) {
                logger.debug("Removed expired bucket for key: {}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * Token bucket implementation
     */
    private static class TokenBucket {
        private final long capacity;
        private final double refillRate; // tokens per millisecond
        private final long windowMillis;

        private double availableTokens;
        private long lastRefillTime;
        private volatile long lastAccessTime;

        TokenBucket(RateLimitConfig config) {
            this.capacity = config.getBurstSize();
            this.windowMillis = config.getWindowInMillis();
            this.refillRate = (double) config.getLimit() / windowMillis;
            this.availableTokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
            this.lastAccessTime = lastRefillTime;
        }

        synchronized boolean tryAcquire(int permits) {
            refill();

            if (availableTokens >= permits) {
                availableTokens -= permits;
                lastAccessTime = System.currentTimeMillis();
                return true;
            }

            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;

            if (elapsed > 0) {
                double tokensToAdd = elapsed * refillRate;
                availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
                lastRefillTime = now;
            }
        }

        RateLimitStatus getStatus() {
            refill();
            long now = System.currentTimeMillis();
            long resetTime = now + (long) ((1 - availableTokens / (double) capacity) * windowMillis);

            return new RateLimitStatus(
                    availableTokens >= 1,
                    (long) availableTokens,
                    capacity,
                    resetTime
            );
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }
    }
}