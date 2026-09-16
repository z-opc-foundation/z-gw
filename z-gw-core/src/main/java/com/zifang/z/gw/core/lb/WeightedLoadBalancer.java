package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加权轮询 — 每个实例按 weight 比例接收流量,Nginx 同款。
 *
 * <p>算法: 平滑加权轮询(SWRR),避免连续多次选同一高权重实例。
 */
public class WeightedLoadBalancer implements LoadBalancer {

    public static final String NAME = "weighted";

    private final ConcurrentHashMap<String, InstanceState> states = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx) {
        if (instances == null || instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.get(0);

        InstanceState state = states.computeIfAbsent(serviceId, k -> new InstanceState(instances.size()));
        synchronized (state) {
            int totalWeight = 0;
            int maxCurrent = Integer.MIN_VALUE;
            int maxIdx = -1;
            for (int i = 0; i < instances.size(); i++) {
                ServiceInstance ins = instances.get(i);
                int w = Math.max(1, ins.getWeight());
                totalWeight += w;
                int current = w + state.currents[i];
                state.currents[i] = current;
                if (current > maxCurrent) {
                    maxCurrent = current;
                    maxIdx = i;
                }
            }
            if (maxIdx == -1) return instances.get(0);
            state.currents[maxIdx] -= totalWeight;
            return instances.get(maxIdx);
        }
    }

    private static class InstanceState {
        final int[] currents;

        InstanceState(int size) {
            this.currents = new int[size];
        }
    }
}
