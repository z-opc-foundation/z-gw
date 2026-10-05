package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流器 — 把 1 秒窗口切成 N 个小槽,累加请求数,过期清理。
 *
 * <p>比固定窗口更精确(避免临界突刺),比令牌桶稍慢(空间换精度)。
 *
 * <p><b>时钟可注入</b>（{@code LongSupplier}，毫秒）。此前直接调
 * {@code System.currentTimeMillis()}，窗口推进只能靠真实时间——要断言"跨过 1 秒后
 * 重新放行"就只能 sleep，既慢又不稳（CI 上 1 秒边界随时会翻车）。</p>
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    public static final String NAME = "slidingWindow";

    private static final int SUB_WINDOWS = 10;          // 每秒切 10 个 100ms 槽
    private static final long SUB_WINDOW_MS = 100L;

    private final int permitsPerWindow;                // 每秒允许数
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, WindowState> states = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int permitsPerSecond) {
        this(permitsPerSecond, System::currentTimeMillis);
    }

    /** 供测试注入确定性时钟；生产用 {@link #SlidingWindowRateLimiter(int)}。 */
    public SlidingWindowRateLimiter(int permitsPerSecond, LongSupplier clockMs) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "permitsPerSecond must be > 0, got " + permitsPerSecond);
        }
        this.permitsPerWindow = permitsPerSecond;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        WindowState st = states.computeIfAbsent(key, k -> new WindowState());
        long now = clockMs.getAsLong();
        synchronized (st) {
            // 清理过期槽
            long boundary = now - 1000L;
            for (int i = 0; i < SUB_WINDOWS; i++) {
                if (st.slots[i].timestamp < boundary) {
                    st.slots[i].timestamp = 0;
                    st.slots[i].count = 0;
                }
            }
            // 累加当前请求数
            int total = 0;
            for (Slot s : st.slots) {
                total += s.count;
            }
            if (total >= permitsPerWindow) {
                return Result.deny(permitsPerWindow, RETRY_AFTER_SECONDS);
            }
            // 找到当前时间槽
            int idx = (int) Math.floorMod(now / SUB_WINDOW_MS, (long) SUB_WINDOWS);
            if (st.slots[idx].timestamp / SUB_WINDOW_MS != now / SUB_WINDOW_MS) {
                st.slots[idx].timestamp = now;
                st.slots[idx].count = 0;
            }
            st.slots[idx].count++;
            return Result.allow(permitsPerWindow - total - 1, permitsPerWindow);
        }
    }

    /**
     * 满窗口时给客户端的重试提示（秒）。
     *
     * <p>取 1 秒——窗口整体跨度就是 1 秒，等满一格必然全滑出去，是安全上界。
     * 真正的"最早能再放行"是窗口里最老那一格的过期时刻（≤1s），但 {@code Retry-After}
     * 是秒粒度，向上取整后与 1 秒无异，写更小的数反而是假精度。</p>
     */
    private static final long RETRY_AFTER_SECONDS = 1L;

    private static class WindowState {
        final Slot[] slots = new Slot[SUB_WINDOWS];
        WindowState() {
            for (int i = 0; i < SUB_WINDOWS; i++) slots[i] = new Slot();
        }
    }

    private static class Slot {
        long timestamp;
        int count;
    }
}
