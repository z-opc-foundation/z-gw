package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RateLimiter;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.factory.RateLimitFilterFactory;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.gw.core.server.GatewayHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 另两个限流器的时钟可注入 + 参数校验 + 429 的 {@code Retry-After} 头。
 *
 * <p>三条线索同源：{@link SlidingWindowRateLimiter} / {@link TokenBucketRateLimiter} 此前
 * 直接调 {@code System.currentTimeMillis()} / {@code System.nanoTime()}，要断言"跨过窗口
 * 后重新放行"只能真等；{@code RateLimiter} 接口注释写着 retryAfterSeconds 是"给 429 响应
 * Retry-After 头"，而全仓没有任何地方写这个头。</p>
 */
class RateLimiterClockInjectionTest {

    /**
     * 假时钟。<b>毫秒与纳秒必须用两个类</b>：曾经合成一个带
     * {@code advanceMs(long ms) { now += ms * 1_000_000; }} 的helper，结果把纳秒
     * 当毫秒喂给了滑动窗口，等于一次跳过 5.8 天，"窗口内不清零"那条直接假绿。
     */
    private static final class FakeNanoClock {
        private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
        long get() { return now.get(); }
        void advanceSec(long s) { now.addAndGet(s * 1_000_000_000L); }
    }

    private static final class FakeMsClock {
        private final AtomicLong now = new AtomicLong(1_000_000_000L);
        long get() { return now.get(); }
        void advanceMs(long ms) { now.addAndGet(ms); }
        void advanceSec(long s) { now.addAndGet(s * 1000L); }
    }

    // ==================================================================
    // 令牌桶：纳秒时钟可注入
    // ==================================================================

    @Test
    @DisplayName("令牌桶：补充速率可由注入时钟驱动（此前只能真等）")
    void tokenBucketRefillDrivenByInjectedClock() {
        FakeNanoClock clock = new FakeNanoClock();
        RateLimiter limiter = new TokenBucketRateLimiter(2 /*容量*/, 1 /*每秒 1 个*/, clock::get);

        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertFalse(limiter.tryAcquire("k").isAllowed(), "桶空时应拒绝");

        clock.advanceSec(1);                       // 补 1 个
        assertTrue(limiter.tryAcquire("k").isAllowed(), "过 1 秒应回一个令牌");
        assertFalse(limiter.tryAcquire("k").isAllowed(), "只回了一个，不该放行第二个");

        clock.advanceSec(3);                       // 再补 3 个，但桶容量 2，多余丢弃
        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertFalse(limiter.tryAcquire("k").isAllowed(), "多余令牌应被桶容量截断");
    }

    @Test
    @DisplayName("令牌桶：时钟不推进就永不回填（证明判定真的读了时钟）")
    void tokenBucketDoesNotRefillWithoutClockAdvance() {
        FakeNanoClock clock = new FakeNanoClock();
        RateLimiter limiter = new TokenBucketRateLimiter(1, 100, clock::get);

        assertTrue(limiter.tryAcquire("k").isAllowed());
        for (int i = 0; i < 20; i++) {
            assertFalse(limiter.tryAcquire("k").isAllowed(),
                    "时钟没动却放行了 —— 限流判定没真正依赖时钟");
        }
    }

    // ==================================================================
    // 令牌桶：参数校验
    // ==================================================================

    @Test
    @DisplayName("令牌桶：补充率<=0 构造即拒（此前静默坏掉并给出 9223372036 秒的重试提示）")
    void tokenBucketRejectsNonPositiveRefillRate() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(20, 0),
                "replenishRate=0 应在构造期就被拒");
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(20, -5),
                "replenishRate=-5 应在构造期就被拒");
    }

    @Test
    @DisplayName("令牌桶：容量<=0 构造即拒（此前被 Math.max(1,…) 静默改成 1）")
    void tokenBucketRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(0, 10), "capacity=0 应在构造期就被拒");
    }

    // ==================================================================
    // 滑动窗口：毫秒时钟可注入
    // ==================================================================

    @Test
    @DisplayName("滑动窗口：跨过 1 秒后重新放行，由注入时钟驱动")
    void slidingWindowReleasesAfterInjectedSecond() {
        // 用 1001ms 而不是 1000ms：清理条件是 timestamp < now-1000（严格小于），
        // 恰好 1000ms 时那一格仍在窗口内。1 秒窗口上差这 1ms 不值得改生产行为，
        // 断言写明确越过边界即可。
        FakeMsClock clock = new FakeMsClock();
        RateLimiter limiter = new SlidingWindowRateLimiter(3, clock::get);

        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertTrue(limiter.tryAcquire("k").isAllowed());
        assertFalse(limiter.tryAcquire("k").isAllowed(), "窗口已满");

        clock.advanceMs(1001);                    // 整窗滑出。
        assertTrue(limiter.tryAcquire("k").isAllowed(), "过 1 秒后应重新放行");
    }

    @Test
    @DisplayName("滑动窗口：窗口内不清零（对照组：证明上条不是被重置逻辑蒙对的）")
    void slidingWindowKeepsCountInsideWindow() {
        FakeMsClock clock = new FakeMsClock();
        RateLimiter limiter = new SlidingWindowRateLimiter(3, clock::get);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("k").isAllowed());
        }
        clock.advanceMs(500);                      // 只过 0.5 秒，还在窗口内
        assertFalse(limiter.tryAcquire("k").isAllowed(),
                "窗口只走了 0.5 秒就放行 —— 1 秒窗口没生效");
    }

    @Test
    @DisplayName("滑动窗口：permitsPerSecond<=0 构造即拒")
    void slidingWindowRejectsNonPositivePermits() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(0), "permitsPerSecond=0 应在构造期就被拒");
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(-1), "permitsPerSecond=-1 应在构造期就被拒");
    }

    @Test
    @DisplayName("无参构造仍走系统时钟（对照组：注入没把生产路径改坏）")
    void defaultConstructorsStillUseSystemClock() {
        assertTrue(new TokenBucketRateLimiter(5, 10).tryAcquire("k").isAllowed());
        assertTrue(new SlidingWindowRateLimiter(5).tryAcquire("k").isAllowed());
    }

    // ==================================================================
    // 429 的 Retry-After 头（端到端）
    // ==================================================================

    @Test
    @DisplayName("429 响应必须带 Retry-After 头（RateLimiter 接口注释承诺过，此前全仓无写入点）")
    void rateLimitedResponseCarriesRetryAfterHeader() throws Exception {
        Map<String, String> args = new HashMap<>();
        args.put("replenishRate", "1");
        args.put("burstCapacity", "1");
        args.put("algorithm", "tokenBucket");

        FullHttpResponse resp = firstRateLimitedResponse(args);
        assertNotNull(resp, "限流后没有响应写出");
        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS, resp.status());
        assertTrue(resp.headers().contains("Retry-After"),
                "429 缺少 Retry-After 头，实际头: " + resp.headers());
        assertTrue(Long.parseLong(resp.headers().get("Retry-After")) >= 0,
                "Retry-After 应是合法的 delta-seconds，实际 " + resp.headers().get("Retry-After"));
    }

    @Test
    @DisplayName("Retry-After 不会变成 9223372036 这种垃圾值（补充率为 0 已改为构造期拒绝）")
    void retryAfterIsNotGarbage() throws Exception {
        Map<String, String> args = new HashMap<>();
        args.put("replenishRate", "1");
        args.put("burstCapacity", "1");
        args.put("algorithm", "tokenBucket");

        FullHttpResponse resp = firstRateLimitedResponse(args);
        long retry = Long.parseLong(resp.headers().get("Retry-After"));
        assertTrue(retry <= 3600L,
                "Retry-After = " + retry + " 秒（~" + (retry / 31536000.0) + " 年）不合理");
    }

    @Test
    @DisplayName("非 429 的错误响应不该带 Retry-After（对照组：没给所有错误乱加头）")
    void nonRateLimitedResponseHasNoRetryAfter() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/nope"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        assertFalse(resp.headers().contains("Retry-After"),
                "404 不该带 Retry-After，实际头: " + resp.headers());
    }

    private static FullHttpResponse firstRateLimitedResponse(Map<String, String> args)
            throws Exception {
        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new com.zifang.z.gw.core.filter.global.ErrorHandlingGlobalFilter());
        GatewayFilter rateLimit = new RateLimitFilterFactory().apply(args);
        assembler.addGlobalFilter(rateLimit);

        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/x").order(1).build()));
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), matcher, assembler, null));

        for (int i = 0; i < 6; i++) {
            ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x"));
            Thread.sleep(60);
            FullHttpResponse r = ch.readOutbound();
            if (r != null) {
                return r;
            }
        }
        for (int i = 0; i < 60; i++) {
            FullHttpResponse r = ch.readOutbound();
            if (r != null) {
                return r;
            }
            Thread.sleep(10);
        }
        return null;
    }
}
