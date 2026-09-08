package com.zifang.z.gw.api;

/**
 * 网关过滤器链 — 通过 {@link GatewayFilterChain#filter(GatewayContext)} 推进到下一节点。
 *
 * <p>典型实现见 core 模块 {@code DefaultGatewayFilterChain}:
 * <pre>{@code
 *   class DefaultGatewayFilterChain implements GatewayFilterChain {
 *       private final List<GatewayFilter> filters;
 *       private final int index;
 *
 *       public void filter(GatewayContext ctx) {
 *           if (index == filters.size()) return;   // 链终止
 *           GatewayFilter next = filters.get(index);
 *           GatewayFilterChain newChain = new DefaultGatewayFilterChain(filters, index + 1);
 *           next.filter(ctx, newChain);
 *       }
 *   }
 * }</pre>
 */
@FunctionalInterface
public interface GatewayFilterChain {

    /**
     * 推进过滤器链。
     *
     * @param ctx 网关上下文
     */
    void filter(GatewayContext ctx);
}
