package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 简易熔断过滤器工厂 — 基于滑动窗口的失败率,失败率超阈值时短路返回 503。
 *
 * <p>yml 例:
 * <pre>
 * filters:
 *   - name: Hystrix
 *     args:
 *       errorThresholdPercentage: 50
 *       requestVolumeThreshold: 20
 *       sleepWindowMs: 5000
 * </pre>
 *
 * <p>这里给一个简化实现;完整的熔断器抽象在 {@link com.zifang.z.gw.core.circuitbreaker}
 * 包内提供独立 SPI,本过滤器用其 in-flight 实现。
 */
public class HystrixFilterFactory implements GatewayFilterFactory {

    private static final Logger log = LoggerFactory.getLogger(HystrixFilterFactory.class);
    public static final String NAME = "Hystrix";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        int errPct = parseInt(args, "errorThresholdPercentage", 50);
        int volume = parseInt(args, "requestVolumeThreshold", 20);
        long sleepMs = parseLong(args, "sleepWindowMs", 5000L);
        String cbName = args.getOrDefault("name", "default");
        return new HystrixFilter(new com.zifang.z.gw.core.circuitbreaker.SlidingWindowCircuitBreaker(
                cbName, errPct, volume, sleepMs));
    }

    private static int parseInt(Map<String, String> args, String k, int d) {
        if (args == null) return d;
        try {
            return Integer.parseInt(args.getOrDefault(k, String.valueOf(d)).trim());
        } catch (Exception e) { return d; }
    }

    private static long parseLong(Map<String, String> args, String k, long d) {
        if (args == null) return d;
        try {
            return Long.parseLong(args.getOrDefault(k, String.valueOf(d)).trim());
        } catch (Exception e) { return d; }
    }

    private static class HystrixFilter implements GatewayFilter {

        private final com.zifang.z.gw.api.CircuitBreaker cb;

        HystrixFilter(com.zifang.z.gw.api.CircuitBreaker cb) {
            this.cb = cb;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 500; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            String key = ctx.getMatchedRoute() != null ? ctx.getMatchedRoute().getId() : "_default";
            if (!cb.allowRequest(key)) {
                throw new GatewayException(503, "SERVICE_UNAVAILABLE", "Circuit breaker open: " + key);
            }
            long start = System.currentTimeMillis();
            try {
                chain.filter(ctx);
                cb.recordSuccess(key, System.currentTimeMillis() - start);
            } catch (Exception e) {
                cb.recordFailure(key, System.currentTimeMillis() - start, e);
                throw e;
            }
        }
    }
}
