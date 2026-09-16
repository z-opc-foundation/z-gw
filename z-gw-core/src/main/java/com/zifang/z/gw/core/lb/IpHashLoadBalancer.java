package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.LoadBalancer;
import com.zifang.z.gw.api.ServiceInstance;

import java.util.List;
import java.util.zip.CRC32;

/**
 * IP 哈希负载均衡 — 同一客户端 IP 始终路由到同一实例,适合会话保持。
 */
public class IpHashLoadBalancer implements LoadBalancer {

    public static final String NAME = "ipHash";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ServiceInstance select(String serviceId, List<ServiceInstance> instances, GatewayContext ctx) {
        if (instances == null || instances.isEmpty()) return null;
        if (instances.size() == 1) return instances.get(0);
        String ip = ctx == null ? null : ctx.getClientIp();
        if (ip == null) ip = "0.0.0.0";

        CRC32 crc = new CRC32();
        crc.update(ip.getBytes());
        long hash = crc.getValue();
        int idx = (int) (Math.abs(hash) % instances.size());
        return instances.get(idx);
    }
}
