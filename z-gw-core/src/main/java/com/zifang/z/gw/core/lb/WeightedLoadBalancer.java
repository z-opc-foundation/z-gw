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
            // 实例数只增不减会怎样：currents 在首次调用时按当时规模定长，服务发现新增实例
            // （扩容、滚动发布）后 instances 变长，state.currents[i] 直接越界，异常从 select
            // 抛出被 NettyProxyFilter 包成 BadGatewayException —— 一次扩容把整条 lb:// 路由打成
            // 502，直到网关重启才恢复。缩容方向只是旧下标残留值，影响分配比例、不抛异常。
            state.ensureCapacity(instances.size());
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
        int[] currents;

        InstanceState(int size) {
            this.currents = new int[size];
        }

        /**
         * 实例数变大时同步扩容，保留已有计数。
         *
         * <p>只在 {@code select} 的 {@code synchronized (state)} 内调用 —— 换数组本身是写操作，
         * 与外层读 {@code currents} 的循环必须互斥。缩容不回收：SWRR 的 currents 本身有界
         * （约 [-totalWeight, maxWeight]），残留值在再扩容时会自行被后续轮次拉回，不值得为它
         * 引入一次拷贝 + 下标重排。</p>
         */
        void ensureCapacity(int size) {
            if (currents.length >= size) return;
            int[] grown = new int[size];
            System.arraycopy(currents, 0, grown, 0, currents.length);
            currents = grown;
        }
    }
}
