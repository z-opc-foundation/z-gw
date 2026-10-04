package com.zifang.z.gw.core.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流器单元测试 — 覆盖令牌桶、滑动窗口、固定窗口 3 种算法。
 */
class RateLimiterTest {

    @Test
    void tokenBucket_capacityBurst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        // 初始 5 个令牌全部放行
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("k1").isAllowed(), "第 " + i + " 个应放行");
        }
        // 第 6 个被拒
        assertFalse(limiter.tryAcquire("k1").isAllowed());
    }

    @Test
    void tokenBucket_isolatedKeys() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 100);
        // k1 耗尽
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed());
        // k2 独立桶,应仍可放行
        assertTrue(limiter.tryAcquire("k2").isAllowed());
        assertTrue(limiter.tryAcquire("k2").isAllowed());
        assertFalse(limiter.tryAcquire("k2").isAllowed());
    }

    @Test
    void slidingWindow_rejectsBeyondLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3);
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed());
    }

    @Test
    void fixedWindow_rejectsBeyondLimit() {
        // 用注入的确定性时钟：旧实现直接读墙上时钟，这条断言依赖
        // "3 次调用恰好落在同一秒内"——在秒边界附近必然不成立（实测红过：
        // expected: <false> but was: <true>）。现在时钟由测试掌控。
        AtomicLong clock = new AtomicLong(1_000_000L);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, clock::get);

        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed());
    }

    @Test
    void fixedWindow_resetsOnNextSecond() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, clock::get);

        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed());

        clock.set(1_001_000L);   // 进入下一秒
        assertTrue(limiter.tryAcquire("k1").isAllowed(), "跨秒后窗口应重置");
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed(), "新窗口的额度也应用完即止");
    }

    @Test
    void fixedWindow_defaultConstructorStillUsesSystemClock() {
        // 不注入时钟的构造器必须照旧工作（API 兼容性）：默认 limit 下第 limit+1 个被拒
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1);
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        // 第 2 个是否被拒取决于是否跨秒，故只断言不抛异常、不返回 null
        assertNotNull(limiter.tryAcquire("k1"));
    }
}
