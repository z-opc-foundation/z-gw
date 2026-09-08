package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 滑动窗口熔断器单元测试。
 */
class CircuitBreakerTest {

    @Test
    void initiallyClosed() {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "test", 50, 10, 5000);
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState("k"));
        assertTrue(cb.allowRequest("k"));
    }

    @Test
    void opensAfterHighFailureRate() {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "test", 50, 5, 60000);
        // 注入 10 个失败,达到 100% 失败率
        for (int i = 0; i < 10; i++) {
            cb.recordFailure("k", 100, new RuntimeException());
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState("k"));
        assertFalse(cb.allowRequest("k"));
    }

    @Test
    void successDoesNotOpen() {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "test", 50, 5, 60000);
        // 注入 10 个成功
        for (int i = 0; i < 10; i++) {
            cb.recordSuccess("k", 100);
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState("k"));
        assertTrue(cb.allowRequest("k"));
    }

    @Test
    void isolatedKeys() {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "test", 50, 5, 60000);
        // k1 触发熔断
        for (int i = 0; i < 10; i++) {
            cb.recordFailure("k1", 100, new RuntimeException());
        }
        assertFalse(cb.allowRequest("k1"));
        // k2 仍可访问
        assertTrue(cb.allowRequest("k2"));
    }
}
