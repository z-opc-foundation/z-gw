package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求日志全局过滤器 — 在请求前后记录 INFO 级访问日志。
 *
 * <p>格式: {@code [reqId] METHOD path -> routeId statusCode costMs}.
 */
public class LoggingGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingGlobalFilter.class);
    public static final String NAME = "Logging";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return -700;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        chain.filter(ctx);
        long cost = System.currentTimeMillis() - start;

        String routeId = ctx.getMatchedRoute() != null ? ctx.getMatchedRoute().getId() : "(none)";
        String status = (String) ctx.getAttribute("resp.status", String.class);
        if (status == null) status = "-";

        log.info("[{}] {} {} -> {} status={} cost={}ms ip={}",
                ctx.getRequestId(),
                ctx.getMethod(),
                ctx.getPath(),
                routeId,
                status,
                cost,
                ctx.getClientIp());
    }
}
