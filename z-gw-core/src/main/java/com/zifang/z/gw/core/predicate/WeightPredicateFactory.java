package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.util.Map;

/**
 * 权重谓词 — 用于灰度发布,按权重分配流量到多条相同 path 的路由。
 *
 * <p>yml 例:
 * <pre>
 * - id: user_service_v1
 *   uri: lb://user-service-v1
 *   predicates:
 *     - Path=/api/user/**
 *     - Weight=user_group,90
 * - id: user_service_v2_canary
 *   uri: lb://user-service-v2
 *   predicates:
 *     - Path=/api/user/**
 *     - Weight=user_group,10
 * </pre>
 *
 * <p>两条路由同一 group(本例 user_group),根据每条权重分流,合计 100。
 */
public class WeightPredicateFactory implements PredicateFactory {

    public static final String NAME = "Weight";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        // args 第一个 key=group, value=weight
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Weight predicate requires group=weight");
        }
        Map.Entry<String, String> first = args.entrySet().iterator().next();
        // weight 评估在 RouteMatcher 的多路由间协调,本谓词只读取 weight 值,
        // 实际放行逻辑见 WeightedRouter;此处总是返回 MATCH,WeightRouter 再协调多路由。
        // 这种简化允许 yml 写法对齐 Spring Cloud Gateway。
        return PredicateResult.match();
    }

    /** 从 args 中提取权重值 */
    public static int extractWeight(Map<String, String> args) {
        if (args == null || args.isEmpty()) return 0;
        String v = args.values().iterator().next();
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 args 中提取 group */
    public static String extractGroup(Map<String, String> args) {
        if (args == null || args.isEmpty()) return "_default";
        return args.keySet().iterator().next();
    }
}
