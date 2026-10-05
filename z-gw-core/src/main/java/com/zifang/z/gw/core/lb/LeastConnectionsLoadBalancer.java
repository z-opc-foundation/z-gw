package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;

/**
 * 最少连接数负载均衡 — 优先选当前活跃连接最少的实例,长连接场景友好。
 *
 * <p>每个实例由 {@link ServiceInstance#incrementActiveConnections()} /
 * {@link ServiceInstance#decrementActiveConnections()} 维护计数;本类自身不持状态,
 * 只读实例上的计数。
 *
 * <p><b>前提是计数真的有人维护</b>：全部实例计数相同时 {@code conns < min} 会在第一个实例
 * 就命中，后续实例全被 {@code conns < min} 的严格小于挡掉，返回 {@code instances.get(0)}。
 * 计数由 {@code NettyProxyFilter} 在选实例后 increment、在转发结束的 finally 里 decrement。</p>
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    public static final String NAME = "leastConnections";

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
