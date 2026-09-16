package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;
import com.zifang.z.gw.api.RateLimiter;
import com.zifang.z.gw.core.ratelimit.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * RequestRateLimiter 过滤器工厂 — 限制请求速率。
 *
 * <p>yml 例:
 * <pre>
 * filters:
 *   - name: RequestRateLimiter
 *     args:
 *       replenishRate: 10
 *       burstCapacity: 20
 *       keyResolver: "ip"     # ip | header:X-User-Id
 *       algorithm: tokenBucket  # tokenBucket | slidingWindow | fixedWindow
 * </pre>
 */
public class RateLimitFilterFactory implements GatewayFilterFactory {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilterFactory.class);
    public static final String NAME = "RequestRateLimiter";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        int replenishRate = parseInt(args, "replenishRate", 10);
        int burstCapacity = parseInt(args, "burstCapacity", 20);
        String keyResolver = args.getOrDefault("keyResolver", "ip");
        String algorithm = args.getOrDefault("algorithm", "tokenBucket");

        RateLimiter limiter;
        switch (algorithm.toLowerCase()) {
            case "slidingwindow":
                limiter = new com.zifang.z.gw.core.ratelimit.SlidingWindowRateLimiter(replenishRate);
                break;
            case "fixedwindow":
                limiter = new com.zifang.z.gw.core.ratelimit.FixedWindowRateLimiter(replenishRate);
                break;
            case "tokenbucket":
            default:
                limiter = new TokenBucketRateLimiter(burstCapacity, replenishRate);
                break;
        }
        return new RateLimitFilter(limiter, keyResolver);
    }

    private static int parseInt(Map<String, String> args, String key, int def) {
        if (args == null) return def;
        String v = args.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static class RateLimitFilter implements GatewayFilter {

        private final RateLimiter limiter;
        private final String keyResolver;

        RateLimitFilter(RateLimiter limiter, String keyResolver) {
            this.limiter = limiter;
            this.keyResolver = keyResolver;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return -100; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            String key = resolveKey(ctx);
            RateLimiter.Result result = limiter.tryAcquire(key);
            if (!result.isAllowed()) {
                throw new GatewayException.RateLimitedException(
                        "Rate limit exceeded for " + key, result.getRetryAfterSeconds());
            }
            chain.filter(ctx);
        }

        private String resolveKey(GatewayContext ctx) {
            if (keyResolver == null || keyResolver.equalsIgnoreCase("ip")) {
                return ctx.getClientIp() == null ? "unknown" : ctx.getClientIp();
            }
            if (keyResolver.startsWith("header:")) {
                String h = keyResolver.substring("header:".length());
                String v = (String) ctx.getAttribute("req.header." + h.toLowerCase());
                return v == null ? "anonymous" : v;
            }
            return keyResolver;
        }
    }
}
