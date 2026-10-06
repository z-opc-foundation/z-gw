package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
    /**
     * 时钟（毫秒）。与 {@code SlidingWindowRateLimiter} / {@code FixedWindowRateLimiter}
     * 同一形状：可注入，于是"窗口什么时候滑"这件事能被判据精确驱动，不必靠 sleep 赌。
     */
    private final LongSupplier clockMs;

    private final ConcurrentHashMap<String, BreakerState> states = new ConcurrentHashMap<>();

    public SlidingWindowCircuitBreaker(String name, int errorThresholdPercentage,
                                       int requestVolumeThreshold, long sleepWindowMs) {
        this(name, errorThresholdPercentage, requestVolumeThreshold, sleepWindowMs, 10, 1000L);
    }

    public SlidingWindowCircuitBreaker(String name, int errorThresholdPercentage,
                                       int requestVolumeThreshold, long sleepWindowMs,
                                       int windowSize, long windowDurationMs) {
        this(name, errorThresholdPercentage, requestVolumeThreshold, sleepWindowMs,
                windowSize, windowDurationMs, System::currentTimeMillis);
    }

    public SlidingWindowCircuitBreaker(String name, int errorThresholdPercentage,
                                       int requestVolumeThreshold, long sleepWindowMs,
                                       int windowSize, long windowDurationMs, LongSupplier clockMs) {
        // 这两个参数此前只有声明和 getter，判定逻辑一次都没读过：窗口实际是
        // "从上次恢复起累计、永不过期"的两只计数器，windowSize/windowDurationMs 是死参数。
        // 现在它们真正参与判定，先把非法值挡在构造期——windowDurationMs 参与除法，
        // 传 0 会让每个请求都撞 ArithmeticException。
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0, got " + windowSize);
        }
        if (windowDurationMs <= 0) {
            throw new IllegalArgumentException("windowDurationMs must be > 0, got " + windowDurationMs);
        }
        if (clockMs == null) {
            throw new IllegalArgumentException("clockMs must not be null");
        }
        this.name = name;
        this.errorThresholdPercentage = errorThresholdPercentage;
        this.requestVolumeThreshold = requestVolumeThreshold;
        this.sleepWindowMs = sleepWindowMs;
        this.windowSize = windowSize;
        this.windowDurationMs = windowDurationMs;
        this.clockMs = clockMs;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean allowRequest(String key) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState(windowSize));
        State current = s.state.get();
        if (current == State.CLOSED) return true;
        if (current == State.OPEN) {
            long elapsed = clockMs.getAsLong() - s.openedAtMs.get();
            if (elapsed >= sleepWindowMs) {
                if (s.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    s.halfOpenSuccess.set(0);
                    s.halfOpenFailure.set(0);
                    // ⚠ 半开期开始新的一个周期：在飞计数必须归零。
                    // 它是从"上一次半开遗留下来"的那个数——若不归零，半开放行的上限
                    // （halfOpenInFlight <= 5）会被上一轮的残留吃掉越来越少。
                    s.halfOpenInFlight.set(0);
                    return true;
                }
                return true;
            }
            return false;
        }
        // HALF_OPEN: 放行有限数
        return s.halfOpenInFlight.incrementAndGet() <= 5;
    }

    /**
     * 归还一个在飞名额，<b>钳到 0 为止</b>。
     * <p>
     * 必须在 recordSuccess / recordFailure 里<b>无条件</b>调用。此前它写在
     * "仅当 current == HALF_OPEN" 的分支里，于是：请求在半开被放行 →
     * 同期另一个请求失败把状态打回 OPEN → 这个请求此刻才回来，
     * {@code current} 已是 OPEN ⇒ <b>名额不归还</b>。
     * 每经历一次这样的翻转就漏 1 个，漏到 5 个之后
     * {@code allowRequest} 的半开判据恒为 false，再也放不进任何请求；
     * 放不进去就没人调 recordSuccess，{@code halfOpenSuccess} 永远到不了 3
     * ⇒ <b>熔断器再也闭不上</b>，后端早已恢复而流量被永远拒之门外。
     * <p>
     * 钳位是因为 CLOSED 状态下进来的请求并没有占用过名额。
     */
    private static void releaseInFlight(BreakerState s) {
        s.halfOpenInFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    @Override
    public void recordSuccess(String key, long durationMs) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState(windowSize));
        releaseInFlight(s);
        State current = s.state.get();
        if (current == State.HALF_OPEN) {
            int ok = s.halfOpenSuccess.incrementAndGet();
            if (ok >= 3) {
                s.state.set(State.CLOSED);
                resetWindow(s);
                s.halfOpenInFlight.set(0);
            }
            return;
        }
        recordToWindow(s, true);
    }

    @Override
    public void recordFailure(String key, long durationMs, Throwable cause) {
        BreakerState s = states.computeIfAbsent(key, k -> new BreakerState(windowSize));
        releaseInFlight(s);
        State current = s.state.get();
        if (current == State.HALF_OPEN) {
            s.halfOpenFailure.incrementAndGet();
            s.state.set(State.OPEN);
            s.openedAtMs.set(clockMs.getAsLong());
            return;
        }
        recordToWindow(s, false);
        if (current == State.CLOSED && shouldTrip(s)) {
            if (s.state.compareAndSet(State.CLOSED, State.OPEN)) {
                s.openedAtMs.set(clockMs.getAsLong());
            }
        }
    }

    @Override
    public State currentState(String key) {
        BreakerState s = states.get(key);
        return s == null ? State.CLOSED : s.state.get();
    }

    // === 内部 ===

    /**
     * 滑动窗口内的失败率是否达到阈值。
     *
     * <p>只统计仍在窗口内的桶——这是 {@code windowSize} / {@code windowDurationMs}
     * 第一次真正起作用的地方：窗口滑走的老失败不再计入，统计不会像原来那样
     * 无限期地累积历史失败率。</p>
     */
    private boolean shouldTrip(BreakerState s) {
        long slotTs = slotOf(s);
        long oldestSlotTs = slotTs - (windowSize - 1);
        long total = 0;
        long failure = 0;
        for (Bucket b : s.buckets) {
            long ts = b.slotTs.get();
            // 未初始化 = 已清空；超出窗口范围 = 已滑出
            if (ts == UNSET_SLOT || ts < oldestSlotTs || ts > slotTs) {
                continue;
            }
            total += b.total.get();
            failure += b.failure.get();
        }
        if (total < requestVolumeThreshold) return false;
        int pct = (int) (failure * 100 / total);
        return pct >= errorThresholdPercentage;
    }

    /** 桶槽位从未被写入过。取 {@link Long#MIN_VALUE} 而非 0：注入时钟时 now 真的可能是 0。 */
    private static final long UNSET_SLOT = Long.MIN_VALUE;

    /**
     * 这个 key 当前所在的片号。
     *
     * <p><b>片起点锚在本 key 的第一次出现</b>，而不是墙钟整除点。此前是
     * {@code System.currentTimeMillis() / windowDurationMs}，于是片边界落在
     * {@code windowDurationMs} 的整数倍上——一个与任何请求都无关的时刻。后果是
     * 同一条请求序列的判定结果<b>取决于它落在周期的哪个位置</b>：</p>
     *
     * <pre>
     * windowSize=1, windowDurationMs=5000，测试里 15 次失败之后 sleep 400ms：
     *   落在片内（多数时候）→ 老失败还在 → OPEN
     *   恰好跨过 5000ms 整除点（概率约 8%）→ 整段统计被清零 → CLOSED
     * </pre>
     * <p>这个 flaky 已经在实测里出现过（复跑 3 次 1 红 2 绿）。改锚点之后，
     * 窗口从"本 key 的第一笔流量"开始对齐，与调用模式无关。</p>
     *
     * <p>一次推进到位（而不是一片一片爬）：长时间空闲后的第一笔要落在当下的片里，
     * 否则被跳过的中间片会带着陈旧计数继续参与统计。</p>
     */
    private long slotOf(BreakerState s) {
        long now = clockMs.getAsLong();
        while (true) {
            long base = s.slotBaseMs.get();
            if (base == UNSET_SLOT) {
                if (s.slotBaseMs.compareAndSet(UNSET_SLOT, now)) {
                    break;
                }
                continue;
            }
            long elapsed = now - base;
            if (elapsed < windowDurationMs) {
                break;
            }
            long k = elapsed / windowDurationMs;
            if (s.slotBaseMs.compareAndSet(base, base + k * windowDurationMs)) {
                s.slotIdx.addAndGet(k);
            }
        }
        return s.slotIdx.get();
    }

    /**
     * 记一次请求到窗口。
     *
     * <p>环形下标 {@code slotTs % windowSize}：同一个槽位被新的一段
     * {@code windowDurationMs} 占用时先清零再计数，这一步就是"滑窗"的滚动。</p>
     */
    private void recordToWindow(BreakerState s, boolean success) {
        long slotTs = slotOf(s);
        Bucket b = s.buckets[(int) Math.floorMod(slotTs, (long) windowSize)];
        if (b.slotTs.getAndSet(slotTs) != slotTs) {
            b.total.set(0L);
            b.failure.set(0L);
        }
        b.total.incrementAndGet();
        if (!success) {
            b.failure.incrementAndGet();
        }
    }

    /**
     * 清空窗口里的计数。
     *
     * <p>只清桶，<b>不动 {@code slotBaseMs}</b>：时间基线要留着继续推进，否则
     * 半开恢复那一刻等于给这个 key 重开一次窗口，老片与新片会接上。</p>
     */
    private void resetWindow(BreakerState s) {
        for (Bucket b : s.buckets) {
            b.slotTs.set(UNSET_SLOT);
            b.total.set(0L);
            b.failure.set(0L);
        }
    }

    private static class BreakerState {
        final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        final AtomicLong openedAtMs = new AtomicLong(0);
        final Bucket[] buckets;
        /**
         * 本 key 的片起点（真实毫秒）与当前片号。
         * <p>每个 key 各有一份：此前片号是全局墙钟算出来的，所有 key 共享同一条片边界。</p>
         */
        final AtomicLong slotBaseMs = new AtomicLong(UNSET_SLOT);
        final AtomicLong slotIdx = new AtomicLong(0L);
        final AtomicInteger halfOpenSuccess = new AtomicInteger(0);
        final AtomicInteger halfOpenFailure = new AtomicInteger(0);
        final AtomicInteger halfOpenInFlight = new AtomicInteger(0);

        BreakerState(int windowSize) {
            this.buckets = new Bucket[windowSize];
            for (int i = 0; i < windowSize; i++) {
                this.buckets[i] = new Bucket();
            }
        }
    }

    /** 窗口里的一个时间桶。{@code slotTs == UNSET_SLOT} 表示这一槽还没被写过。 */
    private static class Bucket {
        final AtomicLong slotTs = new AtomicLong(UNSET_SLOT);
        final AtomicLong total = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
    }
}
