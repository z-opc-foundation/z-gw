package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.FilterDefinition;
import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.RouteDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 过滤器工厂的 args 有两种写法，按语义 key 取值的工厂必须都认。
 *
 * <p>{@code FilterDefinition.of(name, singleArg)} 把整串收成
 * {@code {"_genkey_0": singleArg}}，而 yml 简写语法
 * {@code RequestRateLimiter=replenishRate=10,burstCapacity=20} 走的正是这条路径 ——
 * key 是定长占位符，配置项名和值都挤在 value 里。</p>
 *
 * <p>8 个内置工厂里 4 个只认其中一种：</p>
 * <ul>
 *   <li>{@code StripPrefix} / {@code PrefixPath} 取 {@code args.values()} 首项，两种写法都对</li>
 *   <li>{@code AddRequestHeader} / {@code AddResponseHeader} 已按 {@code _genkey_0} 拆过</li>
 *   <li><b>{@code RequestRateLimiter} / {@code Hystrix} / {@code Retry} 按语义 key 取
 *       （{@code args.get("replenishRate")} 等），简写进来一律取不到 ⇒ 静默落回默认值</b></li>
 * </ul>
 *
 * <p>其中限流那条最要命：把 {@code replenishRate=1,burstCapacity=2} 配进去，
 * 实际生效的是默认的 {@code 10/20} —— 比运维想要的宽 5~10 倍，而且日志里什么异常都没有。</p>
 */
class FilterFactoryShorthandArgTest {

    private static GatewayContext ctx() {
        GatewayContext c = new GatewayContext();
        c.setMethod("GET");
        c.setPath("/api/x");
        c.setRequestId("ff-1");
        c.setClientIp("10.0.0.1");
        c.setMatchedRoute(RouteDefinition.builder().id("r1").uri("http://x").order(1).build());
        return c;
    }

    private static Map<String, String> mapForm(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // === RequestRateLimiter ===

    @Test
    @DisplayName("限流简写 `RequestRateLimiter=replenishRate=1,burstCapacity=2` 要真的限住")
    void rateLimitShorthandIsHonoured() {
        GatewayFilter f = new RateLimitFilterFactory().apply(FilterDefinition
                .of("RequestRateLimiter", "replenishRate=1,burstCapacity=2").getArgs());

        int allowed = 0;
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            try {
                f.filter(ctx(), c -> { });
                allowed++;
            } catch (GatewayException.RateLimitedException e) {
                limited++;
            }
        }
        // 桶容量 2、每秒补 1 个：6 次连打里最多放行 2 次
        assertTrue(limited >= 3,
                "配的是 burstCapacity=2，6 次连打至少该拒 3 次；实测放行 " + allowed
                        + " 拒 " + limited + " —— 配置被忽略时走的是默认 10/20，6 次会全部放行");
    }

    @Test
    @DisplayName("限流的 map 写法照常工作（对照组）")
    void rateLimitMapFormStillWorks() {
        GatewayFilter f = new RateLimitFilterFactory().apply(FilterDefinition
                .of("RequestRateLimiter", mapForm("replenishRate", "1", "burstCapacity", "2")).getArgs());

        int limited = 0;
        for (int i = 0; i < 6; i++) {
            try {
                f.filter(ctx(), c -> { });
            } catch (GatewayException.RateLimitedException e) {
                limited++;
            }
        }
        assertTrue(limited >= 3, "map 写法 key 就是配置项名，不该受影响；实测拒 " + limited);
    }

    @Test
    @DisplayName("限流的 keyResolver 简写也要生效（`keyResolver=header:X-User-Id`）")
    void rateLimitKeyResolverShorthandIsHonoured() {
        GatewayFilter f = new RateLimitFilterFactory().apply(FilterDefinition
                .of("RequestRateLimiter", "replenishRate=1,burstCapacity=2,keyResolver=header:X-User-Id")
                .getArgs());

        GatewayContext c = ctx();
        c.setAttribute("req.header.x-user-id", "u-42");
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            try {
                f.filter(c, x -> { });
            } catch (GatewayException.RateLimitedException e) {
                limited++;
            }
        }
        assertTrue(limited >= 3,
                "配了 keyResolver=header:X-User-Id 时限流键应是请求头里的用户 id；实测拒 " + limited
                        + " —— keyResolver 被忽略时会退回默认的 ip");
    }

    // === Hystrix ===

    @Test
    @DisplayName("熔断简写 `Hystrix=requestVolumeThreshold=1,errorThresholdPercentage=1` 要真的熔断")
    void hystrixShorthandIsHonoured() {
        GatewayFilter f = new HystrixFilterFactory().apply(FilterDefinition
                .of("Hystrix", "errorThresholdPercentage=1,requestVolumeThreshold=1,sleepWindowMs=60000")
                .getArgs());

        int opened = 0;
        for (int i = 0; i < 4; i++) {
            try {
                f.filter(ctx(), c -> {
                    throw new GatewayException(502, "BAD_GATEWAY", "backend down");
                });
            } catch (GatewayException e) {
                if (e.getHttpStatus() == 503) {
                    opened++;   // 熔断器已经打开，后续直接短路
                }
            }
        }
        assertTrue(opened >= 1,
                "配的是 requestVolumeThreshold=1（1 次失败即应熔断），4 次里至少 1 次该被短路成 503；"
                        + "实测 503 次数 " + opened + " —— 参数被忽略时走默认 20/50，4 次打不熔断");
    }

    @Test
    @DisplayName("熔断的 map 写法照常工作（对照组）")
    void hystrixMapFormStillWorks() {
        GatewayFilter f = new HystrixFilterFactory().apply(FilterDefinition
                .of("Hystrix", mapForm("errorThresholdPercentage", "1",
                        "requestVolumeThreshold", "1", "sleepWindowMs", "60000"))
                .getArgs());

        int opened = 0;
        for (int i = 0; i < 4; i++) {
            try {
                f.filter(ctx(), c -> {
                    throw new GatewayException(502, "BAD_GATEWAY", "backend down");
                });
            } catch (GatewayException e) {
                if (e.getHttpStatus() == 503) opened++;
            }
        }
        assertTrue(opened >= 1, "map 写法不该受影响；实测 503 次数 " + opened);
    }

    // === Retry ===

    @Test
    @DisplayName("重试简写 `Retry=retries=5,backoffMs=0` 要真的重试 5 次")
    void retryShorthandIsHonoured() {
        GatewayFilter f = new RetryFilterFactory().apply(FilterDefinition
                .of("Retry", "retries=5,backoffMs=0").getArgs());

        AtomicInteger attempts = new AtomicInteger();
        try {
            f.filter(ctx(), c -> {
                attempts.incrementAndGet();
                throw new GatewayException(502, "BAD_GATEWAY", "backend down");
            });
            fail("后端持续 502 时应最终抛出");
        } catch (GatewayException expected) {
            assertEquals(502, expected.getHttpStatus());
        }
        // 首次 + 5 次重试
        assertEquals(6, attempts.get(),
                "配的是 retries=5，应共尝试 6 次；实测 " + attempts.get()
                        + " 次 —— 参数被忽略时走默认 3，共 4 次");
    }

    @Test
    @DisplayName("重试的 map 写法照常工作（对照组）")
    void retryMapFormStillWorks() {
        GatewayFilter f = new RetryFilterFactory().apply(FilterDefinition
                .of("Retry", mapForm("retries", "5", "backoffMs", "0")).getArgs());

        AtomicInteger attempts = new AtomicInteger();
        try {
            f.filter(ctx(), c -> {
                attempts.incrementAndGet();
                throw new GatewayException(502, "BAD_GATEWAY", "backend down");
            });
            fail("后端持续 502 时应最终抛出");
        } catch (GatewayException expected) {
            assertEquals(502, expected.getHttpStatus());
        }
        assertEquals(6, attempts.get(), "map 写法不该受影响；实测 " + attempts.get() + " 次");
    }

    @Test
    @DisplayName("对照组：StripPrefix / PrefixPath 两种写法本来就都认（证明判据本身有效）")
    void positionalFactoriesWereAlwaysFine() {
        GatewayFilter strip = new StripPrefixFilterFactory()
                .apply(FilterDefinition.of("StripPrefix", "2").getArgs());
        GatewayContext c = ctx();
        c.setPath("/api/user/list");
        strip.filter(c, x -> { });
        assertEquals("/list", c.getPath(), "StripPrefix 简写本就工作（它取 values() 首项）");

        GatewayFilter prefix = new PrefixPathFilterFactory()
                .apply(FilterDefinition.of("PrefixPath", "/gw").getArgs());
        GatewayContext c2 = ctx();
        c2.setPath("/v1/users");
        prefix.filter(c2, x -> { });
        assertEquals("/gw/v1/users", c2.getPath(), "PrefixPath 简写本就工作");
    }
}
