package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateDefinition;
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
        // weight 评估在 RouteMatcher 的多路由间协调,本谓词只读取 weight 值,
        // 实际放行逻辑见 RouteMatcher 的第二遍匹配;此处总是返回 MATCH。
        // 这种简化允许 yml 写法对齐 Spring Cloud Gateway。
        return PredicateResult.match();
    }

    /**
     * 把 args 拆成 {@code [group, weight 文本]}。
     *
     * <p>两种写法都要认:
     * <ul>
     *   <li>yml 简写 {@code Weight=user_group,90} —
     *       {@link PredicateDefinition#of(String, String)} 收成 {@code {"_genkey_0": "user_group,90"}},
     *       key 是定长占位符,两个值都在 value 里</li>
     *   <li>map 写法 {@code args: {user_group: "90"}} — key 就是 group</li>
     * </ul>
     *
     * <p>此前只认 map 写法:简写进来的 group 被原样当成 {@code _genkey_0}(所有灰度组塌成一组),
     * 而 weight 拿整串 {@code "user_group,90"} 做 {@code parseInt} → NumberFormatException
     * 被吞成 0 → {@code selectByWeight} 的 total 恒为 0 → 金丝雀永远拿不到流量。
     * 同仓 {@code AddRequestHeaderFilterFactory} 已按同一约定解析 {@code _genkey_0}。</p>
     */
    private static String[] split(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return new String[]{"_default", ""};
        }
        Map.Entry<String, String> first = args.entrySet().iterator().next();
        String v = first.getValue() == null ? "" : first.getValue();
        if ("_genkey_0".equals(first.getKey())) {
            String[] parts = v.split(",", 2);
            return new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : ""};
        }
        return new String[]{first.getKey(), v.trim()};
    }

    /** 从 args 中提取权重值 */
    public static int extractWeight(Map<String, String> args) {
        String raw = split(args)[1];
        if (raw.isEmpty()) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 args 中提取 group */
    public static String extractGroup(Map<String, String> args) {
        return split(args)[0];
    }
}
