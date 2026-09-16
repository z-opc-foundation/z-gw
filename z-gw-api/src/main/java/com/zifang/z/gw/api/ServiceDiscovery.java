package com.zifang.z.gw.api;

import java.util.List;

/**
 * 服务发现 SPI — 给定 serviceId,返回一组健康实例。
 *
 * <p>实现思路:
 * <ul>
 *   <li>{@code StaticServiceDiscovery} — 静态配置文件</li>
 *   <li>{@code NacosServiceDiscovery} — Nacos 注册中心</li>
 *   <li>{@code ConsulServiceDiscovery} — Consul 健康服务</li>
 *   <li>{@code KubernetesServiceDiscovery} — K8s Endpoints API</li>
 *   <li>{@code ZrpcServiceDiscovery} — 自研 z-rpc 注册中心</li>
 * </ul>
 *
 * <p>路由的 {@code uri=lb://serviceName} 会经过 ServiceDiscovery 解析为具体实例。
 */
public interface ServiceDiscovery {

    /** 获取一个服务的全部实例(含 healthy / unhealthy,调用方按需过滤) */
    List<ServiceInstance> getInstances(String serviceId);

    /** 获取仅健康实例 */
    default List<ServiceInstance> getHealthyInstances(String serviceId) {
        java.util.List<ServiceInstance> all = getInstances(serviceId);
        java.util.List<ServiceInstance> result = new java.util.ArrayList<>(all.size());
        for (ServiceInstance ins : all) {
            if (ins.isHealthy()) result.add(ins);
        }
        return result;
    }

    /**
     * 注册变更监听器 — 服务实例增删/健康状态变化时回调。
     */
    default void addChangeListener(ChangeListener listener) {
        throw new UnsupportedOperationException("This discovery impl does not support listeners");
    }

    @FunctionalInterface
    interface ChangeListener {
        void onChange(String serviceId, List<ServiceInstance> instances);
    }
}
