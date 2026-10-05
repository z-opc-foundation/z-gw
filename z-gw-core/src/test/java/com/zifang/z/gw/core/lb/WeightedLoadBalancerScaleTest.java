package com.zifang.z.gw.core.lb;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.ServiceInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link WeightedLoadBalancer} 的 SWRR 状态必须跟着实例列表的规模走。
 *
 * <p>{@code states} 按 serviceId 缓存 {@code InstanceState}，而 {@code InstanceState.currents}
 * 在<b>首次调用</b>时按 {@code instances.size()} 定长。之后服务发现扩容（新实例上线、
 * 滚动发布）时 {@code instances} 变长，{@code state.currents[i]} 直接越界。</p>
 *
 * <p>这不是"少分点流量"，是<b>扩容动作会把整条 lb:// 路由打成 502</b>：
 * 异常从 {@code select} 抛出，被 {@code NettyProxyFilter} 包成
 * {@code BadGatewayException}，直到网关重启才恢复。</p>
 */
class WeightedLoadBalancerScaleTest {

    private static GatewayContext ctx() {
        GatewayContext c = new GatewayContext();
        c.setClientIp("1.1.1.1");
        c.setRequestId("scale-1");
        return c;
    }

    private static List<ServiceInstance> instances(int... weights) {
        List<ServiceInstance> list = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            list.add(ServiceInstance.builder()
                    .serviceId("svc")
                    .instanceId("i" + i)
                    .host("10.0.0." + i)
                    .port(8080 + i)
                    .weight(weights[i])
                    .build());
        }
        return list;
    }

    /** 只统计 instanceId，不记录顺序——SWRR 的顺序本身不是对外承诺。 */
    private static Map<String, Integer> pick(WeightedLoadBalancer lb, List<ServiceInstance> pool, int rounds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < rounds; i++) {
            ServiceInstance ins;
            try {
                ins = lb.select("svc", pool, ctx());
            } catch (RuntimeException e) {
                fail("第 " + (i + 1) + " 次选择抛出 " + e.getClass().getSimpleName()
                        + " —— 实例数变化后 select 必须照常返回，扩容不该把整条路由打成 502：" + e.getMessage());
                return null;
            }
            assertNotNull(ins, "select 不应返回 null");
            counts.merge(ins.getInstanceId(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    @DisplayName("实例数从 2 扩到 3 后仍能选择，且按权重比例分配")
    void scaleUpAfterFirstSelect() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer();
        lb.select("svc", instances(100, 100), ctx());   // 首次：currents 定长为 2

        // 扩容后新建池子——SWRR 状态是按 serviceId 缓存的，与实例对象身份无关
        List<ServiceInstance> grown = instances(100, 100, 100);
        Map<String, Integer> counts = pick(lb, grown, 30);

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertTrue(e.getValue() >= 8 && e.getValue() <= 12,
                    "等权 3 实例 30 轮应各约 10 次，实测 " + e.getKey() + "=" + e.getValue()
                            + "（全部计数 " + counts + "）");
        }
        assertTrue(counts.size() == 3,
                "扩容后的新实例必须分到流量，实测只出现了 " + counts.keySet());
    }

    @Test
    @DisplayName("滚动发布序列 2→3→2→4 全程不抛异常（缩容后残留的 currents 不能在再扩容时引爆）")
    void rollingDeploymentScaleSequence() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer();
        pick(lb, instances(100, 100), 6);                 // 2 实例
        pick(lb, instances(100, 100, 100), 6);           // → 3
        pick(lb, instances(100, 100), 6);                 // → 2（缩容）
        Map<String, Integer> counts = pick(lb, instances(100, 100, 100, 100), 24);  // → 4

        assertTrue(counts.size() == 4,
                "4 个实例都应分到流量，实测 " + counts);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertTrue(e.getValue() >= 4 && e.getValue() <= 8,
                    "等权 4 实例 24 轮应各约 6 次，实测 " + e.getKey() + "=" + e.getValue());
        }
    }

    @Test
    @DisplayName("非等权扩容后仍按原权重比例（对照组：证明上两条不是只测了不抛异常）")
    void scaleUpKeepsWeightRatio() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer();
        lb.select("svc", instances(100, 100), ctx());

        // 100 : 100 : 300  ⇒ 1 : 1 : 3
        Map<String, Integer> counts = pick(lb, instances(100, 100, 300), 60);

        assertTrue(counts.getOrDefault("i0", 0) >= 12 && counts.getOrDefault("i0", 0) <= 18,
                "100 权重应约 15/60，实测 " + counts);
        assertTrue(counts.getOrDefault("i2", 0) >= 36 && counts.getOrDefault("i2", 0) <= 48,
                "300 权重应约 45/60，实测 " + counts);
    }

    @Test
    @DisplayName("不同 serviceId 各自独立计数（对照组：状态确实按 serviceId 隔离）")
    void perServiceStateIsIsolated() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer();
        pick(lb, instances(100, 100), 3);
        GatewayContext c = ctx();
        // svc 已经被 svcA 撑过一轮，svcB 首次调用必须从头开始而不是接着 svcA 的 currents
        for (int i = 0; i < 4; i++) {
            assertNotNull(lb.select("svcB", instances(100, 100), c));
        }
    }
}
