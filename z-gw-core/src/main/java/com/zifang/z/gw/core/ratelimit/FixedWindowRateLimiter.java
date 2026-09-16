package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定窗口限流器 — 每 1 秒一个窗口,窗口内累计请求数。
 *
 * <p>最快但有临界突刺问题(若窗口临界点流量 2 倍瞬时涌入)。
 */
public class FixedWindowRateLimiter implements RateLimiter {

    public static final String NAME = "fixedWindow";

    private final int limit;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limitPerSecond) {
        this.limit = Math.max(1, limitPerSecond);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryAcquire(String key) {
        long nowSec = System.currentTimeMillis() / 1000L;
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

    private static class Window {
        final AtomicLong windowSecond = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);

        Window(long initialSec) {
            this.windowSecond.set(initialSec);
        }
    }
}
