package com.zifang.z.gw.api;

/**
 * 熔断器 SPI — 用于 {@code CircuitBreaker} 过滤器,保护后端服务不雪崩。
 *
 * <p>状态机:CLOSED(正常) → OPEN(熔断,快速失败) → HALF_OPEN(半开,试探性放行)
 *
 * <p>统计维度基于滑动窗口的错误率/慢调用率。
 */
public interface CircuitBreaker {

    /** 熔断器名 */
    String name();

    /**
     * 检查给定 key 的熔断器当前是否允许请求通过。
     *
     * @return true 允许;false 表示熔断中
     */
    boolean allowRequest(String key);

    /**
     * 记录一次成功。
     */
    void recordSuccess(String key, long durationMs);

    /**
     * 记录一次失败。
     */
    void recordFailure(String key, long durationMs, Throwable cause);

    /**
     * 当前状态(用于 metrics/admin)。
     */
    State currentState(String key);

    enum State {
        CLOSED,    // 正常
        OPEN,      // 熔断
        HALF_OPEN  // 半开
    }
}
