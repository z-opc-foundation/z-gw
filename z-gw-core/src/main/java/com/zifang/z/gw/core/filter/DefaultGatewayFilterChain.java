package com.zifang.z.gw.core.filter;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;

import java.util.Collections;
import java.util.List;

/**
 * 链式调用实现 — 通过 immutable list + index 推进,无栈帧增长。
 *
 * <p>替代 v1 的 {@code FilterChain} 同步嵌套调用,改为 immutable index 风格,
 * 单次请求只产生一个 {@code DefaultGatewayFilterChain} 对象 + N 次 lambda 构造。
 *
 * <p>线程安全:每次 filter 都构造一个新 chain,无共享状态。
 */
public class DefaultGatewayFilterChain implements GatewayFilterChain {

    private final List<GatewayFilter> filters;
    private final int index;

    public DefaultGatewayFilterChain(List<GatewayFilter> filters) {
        this.filters = filters == null ? Collections.<GatewayFilter>emptyList() : filters;
        this.index = 0;
    }

    private DefaultGatewayFilterChain(List<GatewayFilter> filters, int index) {
        this.filters = filters;
        this.index = index;
    }

    @Override
    public void filter(GatewayContext ctx) {
        if (index == filters.size()) {
            return;  // 链终止
        }
        GatewayFilter next = filters.get(index);
        GatewayFilterChain newChain = new DefaultGatewayFilterChain(filters, index + 1);
        // 优先 shouldFilter 短路
        if (next.shouldFilter(ctx)) {
            next.filter(ctx, newChain);
        } else {
            newChain.filter(ctx);
        }
    }

    /** 静态工具:启动过滤器链 */
    public static void execute(List<GatewayFilter> filters, GatewayContext ctx) {
        new DefaultGatewayFilterChain(filters).filter(ctx);
    }
}
