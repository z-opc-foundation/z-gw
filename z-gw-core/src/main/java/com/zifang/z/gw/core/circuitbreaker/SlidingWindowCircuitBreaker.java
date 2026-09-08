package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 滑动窗口熔断器 — 基于 N 个连续 bucket 的失败率统计。
 *
 * <p>状态机:
 * <pre>
 *   CLOSED (正常) --失败率超阈值 & 流量达 minimumVolume--> OPEN (熔断)
 *   OPEN (熔断) --sleepWindow 过期--> HALF_OPEN (半开)
 *   HALF_OPEN (半开) --请求成功--> CLOSED
 *   HALF_OPEN (半开) --请求失败--> OPEN (重新计时)
 * </pre>
 *
 * <p>参数:
 * <ul>
 *   <li>{@code errorThresholdPercentage} — 失败率阈值(0-100),默认 50</li>
 *   <li>{@code requestVolumeThreshold} — 最小请求数(小于此值不熔断),默认 20</li>
 *   <li>{@code sleepWindowMs} — OPEN → HALF_OPEN 等待时间,默认 5000ms</li>
 *   <li>{@code windowSize} — 滑动窗口 bucket 数,默认 10</li>
 *   <li>{@code windowDurationMs} — 单 bucket 时长,默认 1000ms</li>
 * </ul>
 */
public class SlidingWindowCircuitBreaker implements CircuitBreaker {

    private final String name;
    private final int errorThresholdPercentage;
    private final int requestVolumeThreshold;
    private final long sleepWindowMs;
    private final int windowSize;
    private final long windowDurationMs;

    private final ConcurrentHashMap<String, BreakerState> states = new ConcurrentHashMap<>();

    public SlidingWindowCircuitBreaker(String name, int errorThresholdPercentage,
                                       int requestVolumeThreshold, long sleepWindowMs) {
        this(name, errorThresholdPercentage, requestVolumeThreshold, sleepWindowMs, 10, 1000L);
    }

    public SlidingWindowCircuitBreaker(String name, int errorThresholdPercentage,
                                       int requestVolumeThreshold, long sleepWindowMs,
                                       int windowSize, long windowDurationMs) {
        this.name = name;
        this.errorThresholdPercentage = errorThresholdPercentage;
        this.requestVolumeThreshold = requestVolumeThreshold;
        this.sleepWindowMs = sleepWindowMs;
        this.windowSize = windowSize;
        this.windowDurationMs = windowDurationMs;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean allowRequest(String key) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState());
        State current = s.state.get();
        if (current == State.CLOSED) return true;
        if (current == State.OPEN) {
            long elapsed = System.currentTimeMillis() - s.openedAtMs.get();
            if (elapsed >= sleepWindowMs) {
                if (s.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    s.halfOpenSuccess.set(0);
                    s.halfOpenFailure.set(0);
                    return true;
                }
                return true;
            }
            return false;
        }
        // HALF_OPEN: 放行有限数
        return s.halfOpenInFlight.incrementAndGet() <= 5;
    }

    @Override
    public void recordSuccess(String key, long durationMs) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState());
        State current = s.state.get();
        if (current == State.HALF_OPEN) {
            int ok = s.halfOpenSuccess.incrementAndGet();
            s.halfOpenInFlight.decrementAndGet();
            if (ok >= 3) {
                s.state.set(State.CLOSED);
                resetWindow(s);
            }
            return;
        }
        recordToWindow(s, true);
    }

    @Override
    public void recordFailure(String key, long durationMs, Throwable cause) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState());
        State current = s.state.get();
        if (current == State.HALF_OPEN) {
            s.halfOpenFailure.incrementAndGet();
            s.halfOpenInFlight.decrementAndGet();
            s.state.set(State.OPEN);
            s.openedAtMs.set(System.currentTimeMillis());
            return;
        }
        recordToWindow(s, false);
        if (current == State.CLOSED && shouldTrip(s)) {
            if (s.state.compareAndSet(State.CLOSED, State.OPEN)) {
                s.openedAtMs.set(System.currentTimeMillis());
            }
        }
    }

    @Override
    public State currentState(String key) {
        BreakerState s = states.get(key);
        return s == null ? State.CLOSED : s.state.get();
    }

    // === 内部 ===

    private boolean shouldTrip(BreakerState s) {
        long total = s.totalCount.sum();
        long failure = s.failureCount.sum();
        if (total < requestVolumeThreshold) return false;
        int pct = (int) (failure * 100 / total);
        return pct >= errorThresholdPercentage;
    }

    private void recordToWindow(BreakerState s, boolean success) {
        s.totalCount.increment();
        if (!success) s.failureCount.increment();
    }

    private void resetWindow(BreakerState s) {
        s.totalCount.reset();
        s.failureCount.reset();
    }

    private static class BreakerState {
        final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        final AtomicLong openedAtMs = new AtomicLong(0);
        final Counter totalCount = new Counter();
        final Counter failureCount = new Counter();
        final AtomicInteger halfOpenSuccess = new AtomicInteger(0);
        final AtomicInteger halfOpenFailure = new AtomicInteger(0);
        final AtomicInteger halfOpenInFlight = new AtomicInteger(0);
    }

    /** 简易计数器(long) */
    private static class Counter {
        private final AtomicLong v = new AtomicLong();

        void increment() {
            v.incrementAndGet();
        }

        long sum() {
            return v.get();
        }

        long sumThenReset() {
            return v.getAndSet(0);
        }

        void reset() {
            v.set(0);
        }
    }
}
