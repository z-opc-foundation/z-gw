package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器 — 按固定速率补充令牌,桶满则多余令牌丢弃;请求消耗 1 个令牌,空则拒绝。
 *
 * <p>允许突发流量: 桶容量(burst)=最大并发量,replenishRate=每秒补充速率(稳态 QPS)。
 *
 * <p>线程安全: 用 CAS(AtomicLong.compareAndSet)实现无锁令牌发放,适合高并发。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    public static final String NAME = "tokenBucket";

    private final int capacity;
    private final double refillTokensPerNano;  // tokens per nanosecond

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int replenishRatePerSecond) {
        this.capacity = Math.max(1, capacity);
        this.refillTokensPerNano = (double) replenishRatePerSecond / 1_000_000_000.0;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, System.nanoTime()));
        return b.tryAcquire(refillTokensPerNano, capacity);
    }

    /** 单 key 的桶 */
    private static class Bucket {
        final int capacity;
        final AtomicLong tokens;        // 当前令牌数(乘以 1000 保留 3 位精度)
        final AtomicLong lastRefillNs;  // 上次补充时间

        Bucket(int capacity, long now) {
            this.capacity = capacity;
            this.tokens = new AtomicLong((long) capacity * 1000);
            this.lastRefillNs = new AtomicLong(now);
        }

        Result tryAcquire(double refillTokensPerNano, int cap) {
            while (true) {
                long now = System.nanoTime();
                long lastRefill = lastRefillNs.get();
                long elapsedNs = now - lastRefill;
                if (elapsedNs > 0) {
                    // 计算新增令牌数(放大 1000 倍)
                    long addTokens = (long) (elapsedNs * refillTokensPerNano * 1000);
                    if (addTokens > 0) {
                        if (lastRefillNs.compareAndSet(lastRefill, now)) {
                            long newTokens = Math.min((long) cap * 1000, tokens.get() + addTokens);
                            tokens.set(newTokens);
                        }
                    }
                }
                long cur = tokens.get();
                if (cur >= 1000) {
                    if (tokens.compareAndSet(cur, cur - 1000)) {
                        long remaining = tokens.get() / 1000;
                        return Result.allow(remaining, cap);
                    }
                } else {
                    // 计算补充 1 个令牌需要多少时间
                    long needNs = (long) ((1.0 - cur / 1000.0) / refillTokensPerNano);
                    long retrySec = Math.max(1, needNs / 1_000_000_000L);
                    return Result.deny(cap, retrySec);
                }
            }
        }
    }
}
