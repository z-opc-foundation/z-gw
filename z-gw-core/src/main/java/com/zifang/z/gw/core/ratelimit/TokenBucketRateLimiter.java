package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 令牌桶限流器 — 按固定速率补充令牌,桶满则多余令牌丢弃;请求消耗 1 个令牌,空则拒绝。
 *
 * <p>允许突发流量: 桶容量(burst)=最大并发量,replenishRate=每秒补充速率(稳态 QPS)。</p>
 *
 * <p>线程安全: 用 CAS(AtomicLong.compareAndSet)实现无锁令牌发放,适合高并发。</p>
 *
 * <p><b>时钟可注入</b>（{@code LongSupplier}，纳秒）。此前直接调 {@code System.nanoTime()}，
 * 补充速率无法在测试里驱动——要断言"等多久回一个令牌"只能真等。</p>
 */
public class TokenBucketRateLimiter implements RateLimiter {

    public static final String NAME = "tokenBucket";

    private final int capacity;
    private final double refillTokensPerNano;  // tokens per nanosecond
    private final LongSupplier nanoClock;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int replenishRatePerSecond) {
        this(capacity, replenishRatePerSecond, System::nanoTime);
    }

    /** 供测试注入确定性时钟；生产用 {@link #TokenBucketRateLimiter(int, int)}。 */
    public TokenBucketRateLimiter(int capacity, int replenishRatePerSecond, LongSupplier nanoClock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        // replenishRate<=0 此前是静默坏掉：补充率 0 ⇒ 桶永不回填，且算"补一个令牌要多久"
        // 时除以 0 得 Infinity，再 (long) 转换得到 Long.MAX_VALUE ⇒ 429 的 retryAfterSeconds
        // 变成 9223372036（约 292 年）。负速率同理永不回填，却报"1 秒后重试"。
        // 实测：TokenBucketRateLimiter(20, 0) 连打 25 次后 getRetryAfterSeconds() = 9223372036。
        if (replenishRatePerSecond <= 0) {
            throw new IllegalArgumentException(
                    "replenishRatePerSecond must be > 0, got " + replenishRatePerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerNano = (double) replenishRatePerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong()));
        return b.tryAcquire(refillTokensPerNano, capacity, nanoClock);
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

        Result tryAcquire(double refillTokensPerNano, int cap, LongSupplier nanoClock) {
            while (true) {
                long now = nanoClock.getAsLong();
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
