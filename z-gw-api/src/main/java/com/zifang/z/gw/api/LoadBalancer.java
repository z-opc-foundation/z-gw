package com.zifang.z.gw.api;

import java.util.List;

/**
 * 负载均衡 SPI — 从一组实例中选择一个。
 *
 * <p>内置实现(在 core 模块):
 * <ul>
 *   <li>{@code RandomLoadBalancer} — 随机</li>
 *   <li>{@code RoundRobinLoadBalancer} — 轮询</li>
 *   <li>{@code WeightedLoadBalancer} — 加权轮询</li>
 *   <li>{@code IpHashLoadBalancer} — IP 哈希(会话保持)</li>
 *   <li>{@code LeastConnectionsLoadBalancer} — 最少连接</li>
 * </ul>
 */
public interface LoadBalancer {

    /** 算法名,yml 引用 */
    String name();

    /**
     * 从 instances 中选择一个实例。
     *
     * @param serviceId 服务 ID(可用于 sticky session 持久化)
     * @param instances 候选实例列表,可能为空
     * @param ctx 上下文(GatewayContext,可取 clientIp 用于 hash)
     * @return 选中的实例;若列表为空返回 null
     */
    ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx);
}
