package com.zifang.z.gw.api;

/**
 * 限流器 SPI — 用于 {@code RequestRateLimiter} 过滤器。
 *
 * <p>核心能力:
 * <ul>
 *   <li>acquire(key) — 尝试获取一个令牌,成功返回 true</li>
 *   <li>返回 {@link Result} 含 retryAfterSeconds 给 429 响应 Retry-After 头</li>
 * </ul>
 *
 * <p>内置实现(在 core 模块):
 * <ul>
 *   <li>{@code TokenBucketRateLimiter} — 令牌桶(允许突发)</li>
 *   <li>{@code SlidingWindowRateLimiter} — 滑动窗口(精确)</li>
 *   <li>{@code FixedWindowRateLimiter} — 固定窗口(最快)</li>
 * </ul>
 */
public interface RateLimiter {

    /** 算法名,yml 引用 */
    String name();

    /**
     * 尝试获取许可。
     */
    Result tryAcquire(String key);

    /**
     * 限流结果。
     */
    final class Result {
        private final boolean allowed;
        private final long remaining;
        private final long limit;
        private final long retryAfterSeconds;

        private Result(boolean allowed, long remaining, long limit, long retryAfterSeconds) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.limit = limit;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static Result allow(long remaining, long limit) {
            return new Result(true, remaining, limit, 0);
        }

        public static Result deny(long limit, long retryAfterSeconds) {
            return new Result(false, 0, limit, retryAfterSeconds);
        }

        public boolean isAllowed() { return allowed; }
        public long getRemaining() { return remaining; }
        public long getLimit() { return limit; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
