package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口限流器 — 把 1 秒窗口切成 N 个小槽,累加请求数,过期清理。
 *
 * <p>比固定窗口更精确(避免临界突刺),比令牌桶稍慢(空间换精度)。
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    public static final String NAME = "slidingWindow";

    private static final int SUB_WINDOWS = 10;          // 每秒切 10 个 100ms 槽
    private final int permitsPerWindow;                // 每秒允许数
    private final ConcurrentHashMap<String, WindowState> states = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int permitsPerSecond) {
        this.permitsPerWindow = Math.max(1, permitsPerSecond);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        WindowState st = states.computeIfAbsent(key, k -> new WindowState());
        long now = System.currentTimeMillis();
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
                return Result.deny(permitsPerWindow, 1);
            }
            // 找到当前时间槽
            int idx = (int) ((now / 100L) % SUB_WINDOWS);
            if (st.slots[idx].timestamp / 100L != now / 100L) {
                st.slots[idx].timestamp = now;
                st.slots[idx].count = 0;
            }
            st.slots[idx].count++;
            return Result.allow(permitsPerWindow - total - 1, permitsPerWindow);
        }
    }

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
