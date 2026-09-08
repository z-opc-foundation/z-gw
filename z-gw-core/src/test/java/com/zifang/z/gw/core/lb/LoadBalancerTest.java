package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 负载均衡器单元测试。
 */
class LoadBalancerTest {

    private List<ServiceInstance> instances(int n) {
        List<ServiceInstance> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(ServiceInstance.builder()
                    .serviceId("svc")
                    .instanceId("i" + i)
                    .host("10.0.0." + i)
                    .port(8080 + i)
                    .build());
        }
        return list;
    }

    private GatewayContext ctx(String ip) {
        GatewayContext c = new GatewayContext();
        c.setClientIp(ip);
        c.setRequestId("req-1");
        return c;
    }

    @Test
    void random_distributesAcrossAll() {
        RandomLoadBalancer lb = new RandomLoadBalancer();
        int[] counts = new int[10];
        for (int i = 0; i < 1000; i++) {
            ServiceInstance ins = lb.select("svc", instances(10), ctx("1.1.1.1"));
            counts[Integer.parseInt(ins.getInstanceId().substring(1))]++;
        }
        for (int c : counts) {
            assertTrue(c > 50 && c < 200, "random 应均匀,实测 c=" + c);
        }
    }

    @Test
    void roundRobin_cyclesThroughAll() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        ServiceInstance first = lb.select("svc", instances(3), ctx("1.1.1.1"));
        ServiceInstance second = lb.select("svc", instances(3), ctx("1.1.1.1"));
        ServiceInstance third = lb.select("svc", instances(3), ctx("1.1.1.1"));
        ServiceInstance fourth = lb.select("svc", instances(3), ctx("1.1.1.1"));
        assertEquals(first.getInstanceId(), fourth.getInstanceId());
        assertNotEquals(first.getInstanceId(), second.getInstanceId());
        assertNotEquals(first.getInstanceId(), third.getInstanceId());
    }

    @Test
    void ipHash_sameIpLandsOnSameInstance() {
        IpHashLoadBalancer lb = new IpHashLoadBalancer();
        ServiceInstance a = lb.select("svc", instances(5), ctx("192.168.1.100"));
        ServiceInstance b = lb.select("svc", instances(5), ctx("192.168.1.100"));
        assertEquals(a.getInstanceId(), b.getInstanceId());
    }

    @Test
    void emptyList_returnsNull() {
        assertNull(new RoundRobinLoadBalancer().select("svc", new ArrayList<>(), ctx("x")));
    }
}
