package com.zifang.z.gw.api;

import java.util.Map;

/**
 * 网关过滤器 SPI — 通过 {@link GatewayFilterChain} 串联,既可作为路由级过滤器(yml 配置),
 * 也可作为 {@link GlobalFilter} 全局过滤器(代码注册)。
 *
 * <p>设计参考 Spring Cloud Gateway 的 {@code GatewayFilter}:
 * <ul>
 *   <li>每个过滤器可读/写 {@link GatewayContext} 任意 attribute</li>
 *   <li>通过 {@code chain.filter(ctx)} 调用下一节点</li>
 *   <li>不调用 {@code chain.filter(ctx)} 即短路响应(用于鉴权失败/限流)</li>
 * </ul>
 */
public interface GatewayFilter {

    /** 过滤器名(用于日志/调试) */
    String name();

    /**
     * 过滤器排序 — 同级过滤器按此值升序链式执行,越小越靠前。
     * <p>常用区间参考(越小越早执行):
     * <ul>
     *   <li>TracingFilter:    -1000 (最早)</li>
     *   <li>MetricsFilter:    -900</li>
     *   <li>RateLimitFilter:  -100</li>
     *   <li>AuthFilter:          0</li>
     *   <li>StripPrefixFilter:  100</li>
     *   <li>RewritePathFilter:  200</li>
     *   <li>CircuitBreaker:     500</li>
     *   <li>ProxyFilter:        999 (最晚,实际转发)</li>
     * </ul>
     */
    int order();

    /**
     * 是否对当前请求执行本过滤器(快速跳过,避免无谓的 chain 进栈)
     */
    default boolean shouldFilter(GatewayContext ctx) {
        return true;
    }

    /**
     * 过滤器逻辑。
     *
     * @param ctx 网关上下文
     * @param chain 过滤器链,本过滤器处理完后调用 {@code chain.filter(ctx)} 推进
     */
    void filter(GatewayContext ctx, GatewayFilterChain chain);
}
