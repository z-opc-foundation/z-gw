package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 固定窗口限流器 — 每 1 秒一个窗口,窗口内累计请求数。
 *
 * <p>最快但有临界突刺问题(若窗口临界点流量 2 倍瞬时涌入)。
 *
 * <p><b>时钟可注入</b>（{@code LongSupplier}，毫秒）：窗口按
 * {@code clock / 1000} 对齐,没有注入就只能靠"3 次调用恰好落在同一秒内"来写测试——
 * 那个假设在秒边界附近必然不成立,实测确实红过。
 */
public class FixedWindowRateLimiter implements RateLimiter {

    public static final String NAME = "fixedWindow";

    private final int limit;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limitPerSecond) {
        this(limitPerSecond, System::currentTimeMillis);
    }

    /** 供测试注入确定性时钟；生产用 {@link #FixedWindowRateLimiter(int)}。 */
    public FixedWindowRateLimiter(int limitPerSecond, LongSupplier clockMs) {
        this.limit = Math.max(1, limitPerSecond);
        this.clockMs = clockMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        long nowSec = clockMs.getAsLong() / 1000L;
        sweepIdleWindows(nowSec);
        Window w = windows.computeIfAbsent(key, k -> new Window(nowSec));
        synchronized (w) {
            if (w.windowSecond.get() != nowSec) {
                w.windowSecond.set(nowSec);
                w.count.set(0);
            }
            int c = w.count.incrementAndGet();
            if (c > limit) {
                return Result.deny(limit, 1);
            }
            return Result.allow(limit - c, limit);
        }
    }

    // ==================================================================
    // 空闲 key 清理
    // ==================================================================

    /**
     * 清掉「窗口已经过去」的条目 —— <b>这些条目不携带任何信息</b>：
     * {@link Window} 只有一个 {@code windowSecond} 与一个计数，
     * 而 {@link #tryAcquire} 每次进来都会在窗口对不上时把计数归零。
     * 所以删掉一个过期条目与"留着它、等下一次访问时被归零"结果完全相同，
     * <b>对限流行为零影响</b>，唯一变化是不再永久占内存。
     */
    private void sweepIdleWindows(long nowSec) {
        if (windows.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            if (w != null && w.windowSecond.get() < nowSec) {
                // 条件删除：万一这个 key 刚被别的线程重建过，别把新条目删掉
                windows.remove(e.getKey(), w);
            }
        }
    }

    /**
     * 扫一遍的门槛。取值只影响"多久扫一次"（扫完 map 就缩回阈值以下，
     * 所以之后不会反复扫），不影响限流语义。
     */
    static final int CLEANUP_THRESHOLD = 1024;

    /** 当前被跟踪的 key 数（运维/测试观测用；生产无害）。 */
    int trackedKeys() {
        return windows.size();
    }

    private static class Window {
        final AtomicLong windowSecond = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);

        Window(long initialSec) {
            this.windowSecond.set(initialSec);
        }
    }
}
