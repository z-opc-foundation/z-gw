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
 * 重试过滤器 — 对可重试异常(5xx、connect timeout)做 N 次重试。
 *
 * <p>yml 例:
 * <pre>
 * filters:
 *   - name: Retry
 *     args:
 *       retries: 3
 *       backoffMs: 100
 * </pre>
 */
public class RetryFilterFactory implements GatewayFilterFactory {

    private static final Logger log = LoggerFactory.getLogger(RetryFilterFactory.class);
    public static final String NAME = "Retry";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        int retries = parseInt(args, "retries", 3);
        long backoffMs = parseLong(args, "backoffMs", 100L);
        return new RetryFilter(retries, backoffMs);
    }

    private static int parseInt(Map<String, String> args, String k, int d) {
        if (args == null) return d;
        try { return Integer.parseInt(args.getOrDefault(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; }
    }
    private static long parseLong(Map<String, String> args, String k, long d) {
        if (args == null) return d;
        try { return Long.parseLong(args.getOrDefault(k, String.valueOf(d)).trim()); } catch (Exception e) { return d; }
    }

    private static class RetryFilter implements GatewayFilter {

        private final int retries;
        private final long backoffMs;

        RetryFilter(int retries, long backoffMs) {
            this.retries = retries;
            this.backoffMs = backoffMs;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 600; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    chain.filter(ctx);
                    return;
                } catch (GatewayException e) {
                    if (!isRetryable(e) || attempt > retries) {
                        throw e;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] retry attempt={} cause={}", ctx.getRequestId(), attempt, e.getMessage());
                    }
                    try {
                        Thread.sleep(backoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }

        private boolean isRetryable(GatewayException e) {
            int s = e.getHttpStatus();
            return s == 502 || s == 503 || s == 504 || s == 500;
        }
    }
}
