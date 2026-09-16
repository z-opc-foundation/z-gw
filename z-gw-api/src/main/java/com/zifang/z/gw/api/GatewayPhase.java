package com.zifang.z.gw.api;

/**
 * 统一响应式工具:阶段标志位 — Netty pipeline 不使用响应式流,
 * 但通过这个枚举让过滤器链可以指示当前阶段,便于 debug/filter 顺序调度。
 */
public enum GatewayPhase {
    /** Netty handler 接收到完整 HTTP 请求后,尚未匹配路由 */
    PRE_ROUTING,
    /** 已匹配到目标路由,准备执行过滤器链 */
    ROUTING,
    /** 过滤器链中,正在转发到后端 */
    POST_ROUTING,
    /** 写出响应阶段 */
    WRITE_RESPONSE,
    /** 出错兜底 */
    ERROR
}
