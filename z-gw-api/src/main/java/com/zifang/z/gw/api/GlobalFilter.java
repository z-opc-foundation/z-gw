package com.zifang.z.gw.api;

/**
 * 全局过滤器 — 不需要 yml 配置,代码注册后对所有路由生效。
 *
 * <p>典型用途:链路追踪、指标采集、错误兜底。
 *
 * <p>与路由级 {@link GatewayFilter} 的区别:全局过滤器通过 {@link GlobalFilter#order()}
 * 决定全局插入位置,系统将其包裹的代理实现插入每条路由的过滤器链中。
 */
public interface GlobalFilter extends GatewayFilter {
}
