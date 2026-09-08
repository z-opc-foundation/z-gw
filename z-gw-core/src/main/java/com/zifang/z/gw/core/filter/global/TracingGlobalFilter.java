package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 链路追踪全局过滤器 — 生成/透传 request id,记录请求起始时间。
 *
 * <p>优先级最高(order=-1000),先于所有其他过滤器。
 */
public class TracingGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(TracingGlobalFilter.class);

    public static final String NAME = "Tracing";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return -1000;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        if (ctx.getRequestId() == null) {
            ctx.setRequestId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
        ctx.setAttribute("trace.start.ms", System.currentTimeMillis());
        if (log.isDebugEnabled()) {
            log.debug("[{}] >>> {} {}", ctx.getRequestId(), ctx.getMethod(), ctx.getUri());
        }
        chain.filter(ctx);
        long cost = System.currentTimeMillis() - (Long) ctx.getAttribute("trace.start.ms");
        ctx.setAttribute("trace.cost.ms", cost);
        if (log.isDebugEnabled()) {
            log.debug("[{}] <<< {} {} cost={}ms", ctx.getRequestId(), ctx.getMethod(), ctx.getUri(), cost);
        }
    }
}
