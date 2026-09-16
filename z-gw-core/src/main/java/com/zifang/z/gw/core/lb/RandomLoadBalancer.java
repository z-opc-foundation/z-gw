package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡 — 等概率选一,实现最简单,适合后端机器性能相近场景。
 */
public class RandomLoadBalancer implements LoadBalancer {

    public static final String NAME = "random";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx) {
        if (instances == null || instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.get(0);
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
