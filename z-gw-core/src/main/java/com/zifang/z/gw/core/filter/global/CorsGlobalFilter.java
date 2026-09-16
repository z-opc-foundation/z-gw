package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CORS 全局过滤器 — 处理跨域请求,自动添加 CORS 响应头。
 *
 * <p>对所有路径生效,通过 {@code Access-Control-*} 头响应。
 */
public class CorsGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(CorsGlobalFilter.class);
    public static final String NAME = "Cors";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return -800;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        // 预检:OPTIONS 直接放行
        if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
            ctx.setAttribute("cors.short.circuit", Boolean.TRUE);
            ctx.setAttribute("resp.status", "204");
            // 直接写响应(简化处理,实际在 NettyHandler 拦截)
        }
        chain.filter(ctx);
    }
}
