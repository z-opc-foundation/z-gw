package com.zifang.z.gw.core.ratelimit;

import org.junit.jupiter.api.Test;

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
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2);
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertTrue(limiter.tryAcquire("k1").isAllowed());
        assertFalse(limiter.tryAcquire("k1").isAllowed());
    }
}
