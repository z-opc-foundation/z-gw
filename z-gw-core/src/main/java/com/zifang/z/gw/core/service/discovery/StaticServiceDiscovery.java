package com.zifang.z.gw.core.service.discovery;

import com.zifang.z.gw.api.ServiceDiscovery;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态服务发现 — 通过代码或 yml 注册的服务实例列表。
 *
 * <p>适合本地开发、单体服务场景;生产环境应替换为 Nacos / Consul / Kubernetes 版本。
 */
public class StaticServiceDiscovery implements ServiceDiscovery {

    private final Map<String, List<ServiceInstance>> services = new ConcurrentHashMap<>();

    public StaticServiceDiscovery register(ServiceInstance instance) {
        if (instance == null || instance.getServiceId() == null) return this;
        services.computeIfAbsent(instance.getServiceId(), k -> new ArrayList<>()).add(instance);
        return this;
    }

    public StaticServiceDiscovery registerAll(List<ServiceInstance> instances) {
        if (instances != null) {
            for (ServiceInstance ins : instances) register(ins);
        }
        return this;
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
        List<ServiceInstance> list = services.get(serviceId);
        if (list == null) return java.util.Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public StaticServiceDiscovery remove(String serviceId, String instanceId) {
        List<ServiceInstance> list = services.get(serviceId);
        if (list == null) return this;
        synchronized (list) {
            list.removeIf(i -> instanceId == null || instanceId.equals(i.getInstanceId()));
        }
        return this;
    }
}
