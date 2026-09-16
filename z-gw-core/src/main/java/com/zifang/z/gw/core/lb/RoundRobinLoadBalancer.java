package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡 — 顺序循环,适合后端机器性能相近 + QPS 均匀。
 *
 * <p>每个 serviceId 独立计数,避免不同服务的轮询序列相互干扰。
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    public static final String NAME = "roundRobin";

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx) {
        if (instances == null || instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.get(0);
        AtomicInteger c = counters.computeIfAbsent(serviceId, k -> new AtomicInteger(0));
        int idx = (c.getAndIncrement() & Integer.MAX_VALUE) % instances.size();
        return instances.get(idx);
    }
}
