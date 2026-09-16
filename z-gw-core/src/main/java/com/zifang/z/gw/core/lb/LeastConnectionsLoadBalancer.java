package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少连接数负载均衡 — 优先选当前活跃连接最少的实例,长连接场景友好。
 *
 * <p>每个实例由 {@link ServiceInstance#incrementActiveConnections()} /
 * {@link ServiceInstance#decrementActiveConnections()} 维护计数。
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    public static final String NAME = "leastConnections";

    private final ConcurrentHashMap<String, AtomicInteger> minTarget = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx) {
        if (instances == null || instances.isEmpty()) return null;
        ServiceInstance best = null;
        int min = Integer.MAX_VALUE;
        for (ServiceInstance ins : instances) {
            int conns = ins.getActiveConnections();
            if (conns < min) {
                min = conns;
                best = ins;
            }
        }
        return best != null ? best : instances.get(0);
    }
}
