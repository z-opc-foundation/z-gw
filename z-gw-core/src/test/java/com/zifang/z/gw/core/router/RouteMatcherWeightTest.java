package com.zifang.z.gw.core.router;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.runtime.YamlRouteLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度权重要真的按配置的分流，且不能把流量泄给不匹配的路由。
 *
 * <p>根因在 {@code PredicateDefinition.of(name, singleArg)}：yml 简写
 * {@code Weight=user_group,90} 被收成 {@code {"_genkey_0": "user_group,90"}}，
 * 而 {@code WeightPredicateFactory.extractGroup} 取 key、{@code extractWeight} 对
 * 整串 {@code "user_group,90"} 做 {@code parseInt} → NumberFormatException → 返回 0。
 * 于是 {@code selectByWeight} 的 total 恒为 0，命中
 * {@code if (total <= 0) return groupRoutes.get(0)}，
 * <b>金丝雀路由永远拿不到流量</b>，group 也全塌成常量 {@code _genkey_0}。</p>
 *
 * <p>仓内 {@link YamlRouteLoader#defaultRoutes()} 自带的演示灰度
 * （v1 权重 90 / v2-canary 权重 10）就是这样一条拿不到流量的路由。</p>
 */
class RouteMatcherWeightTest {

    private static final int ROUNDS = 1000;

    private static GatewayContext ctx(String path, String requestId) {
        GatewayContext c = new GatewayContext();
        c.setMethod("GET");
        c.setPath(path);
        c.setRequestId(requestId);
        c.setHost("gw.test");
        return c;
    }

    private static RouteMatcher matcher(RouteDefinition... routes) {
        RouteMatcher m = new RouteMatcher();
        m.refresh(Arrays.asList(routes));
        return m;
    }

    private static Map<String, Integer> tally(RouteMatcher m, String path) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < ROUNDS; i++) {
            RouteDefinition r = m.route(ctx(path, "req-" + i));
            assertNotNull(r, "第 " + i + " 次请求没匹配到任何路由");
            counts.merge(r.getId(), 1, Integer::sum);
        }
        return counts;
    }

    private static RouteDefinition route(String id, String path, String weight, int order) {
        return RouteDefinition.builder()
                .id(id).uri("http://" + id).order(order)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", path),
                        PredicateDefinition.of("Weight", weight)))
                .build();
    }

    @Test
    @DisplayName("90/10 的灰度：金丝雀必须真的拿到约 10% 流量")
    void canaryReceivesItsShareOfTraffic() {
        RouteMatcher m = matcher(
                route("v1", "/x", "g,90", 1),
                route("v2", "/x", "g,10", 2));

        Map<String, Integer> counts = tally(m, "/x");

        int stable = counts.getOrDefault("v1", 0);
        int canary = counts.getOrDefault("v2", 0);
        assertEquals(ROUNDS, stable + canary, "所有请求都应被路由，实际 " + counts);
        assertTrue(canary > 50 && canary < 150,
                "10% 权重在 " + ROUNDS + " 次里应约 100 次，实测 v2=" + canary + "（全部 " + counts
                        + "）—— 权重没被解析出来时这里恒为 0，金丝雀一条流量都收不到");
    }

    @Test
    @DisplayName("仓内自带的演示灰度配置（v1 90 / canary 10）必须真的分流")
    void shippedDefaultCanaryRouteReceivesTraffic() {
        RouteMatcher m = matcher(YamlRouteLoader.defaultRoutes().toArray(new RouteDefinition[0]));

        Map<String, Integer> counts = tally(m, "/api/users/7");

        int canary = counts.getOrDefault("user-service-v2-canary", 0);
        assertTrue(canary > 50,
                "YamlRouteLoader.defaultRoutes() 里明写 Weight=user_group,10 的金丝雀路由"
                        + "必须拿到约 10% 流量，实测 " + canary + "（全部 " + counts + "）");
        assertTrue(counts.getOrDefault("user-service-v1", 0) > 500, "稳定版应拿大头，实际 " + counts);
    }

    @Test
    @DisplayName("非等权灰度按真实比例分（80/20）")
    void nonEqualWeightsSplitProportionally() {
        RouteMatcher m = matcher(
                route("v1", "/x", "g,80", 1),
                route("v2", "/x", "g,20", 2));

        Map<String, Integer> counts = tally(m, "/x");
        int v2 = counts.getOrDefault("v2", 0);
        assertTrue(v2 > 150 && v2 < 250,
                "20% 权重在 " + ROUNDS + " 次里应约 200 次，实测 v2=" + v2 + "（" + counts + "）");
    }

    @Test
    @DisplayName("同 path 上挂了多个灰度组：按 order 命中的第一个组分流，后面的组不参与")
    void firstMatchingGroupWins() {
        // 第二遍循环一命中就 return，groupB 永远轮不到。把这个语义钉住：
        // 若 group 解析退化成常量（所有 Weight 谓词塌成一组），四条路由会一起进同组，
        // 分配比例从 90/10 变成 50/25/12.5/12.5，a2 会跑到 250 上下而这里就红。
        RouteMatcher m = matcher(
                route("a1", "/x", "groupA,90", 1),
                route("a2", "/x", "groupA,10", 2),
                route("b1", "/x", "groupB,90", 3),
                route("b2", "/x", "groupB,10", 4));

        Map<String, Integer> counts = tally(m, "/x");

        assertEquals(0, counts.getOrDefault("b1", 0) + counts.getOrDefault("b2", 0),
                "同 path 命中第一个组后就返回，后面的组不参与；实际 " + counts);
        int a2 = counts.getOrDefault("a2", 0);
        assertTrue(a2 > 50 && a2 < 150,
                "groupA 内部应按 90/10 分流（约 100/1000），实测 a2=" + a2 + "（" + counts + "）");
    }

    // === 护栏：权重一旦真的生效，selectByWeight 就可能选到不匹配的路由 ===

    @Test
    @DisplayName("同组内谓词不一致时，权重选择不得返回本请求不匹配的路由")
    void weightSelectionNeverReturnsNonMatchingRoute() {
        // 两条路由同属 group g，但 path 不同 —— 配置没约束同组成员谓词相同，
        // 而 groupedByWeight 收的是「所有带 Weight 谓词的路由」，整组拿去加权
        // 就意味着 /a 的请求会有 10% 被送到 /b 去。
        RouteMatcher m = matcher(
                route("a", "/a/**", "g,90", 1),
                route("b", "/b/**", "g,10", 2));

        int leaked = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < ROUNDS; i++) {
            RouteDefinition r = m.route(ctx("/a/x", "req-" + i));
            if (r == null || !"a".equals(r.getId())) {
                leaked++;
                if (detail.length() < 200) detail.append(r == null ? "null " : r.getId() + " ");
            }
        }
        assertEquals(0, leaked,
                "有 " + leaked + "/" + ROUNDS + " 个 /a 的请求被选到了不匹配的路由：" + detail);
    }

    @Test
    @DisplayName("同组谓词一致时，权重选择仍按比例（对照组：护栏没有把正常灰度也一起掐死）")
    void matchingGroupStillSplitsByWeight() {
        RouteMatcher m = matcher(
                route("v1", "/a/**", "g,90", 1),
                route("v2", "/a/**", "g,10", 2));

        Map<String, Integer> counts = tally(m, "/a/x");
        int v2 = counts.getOrDefault("v2", 0);
        assertTrue(v2 > 50 && v2 < 150,
                "谓词一致时金丝雀仍应拿到约 10%，实测 v2=" + v2 + "（" + counts + "）");
    }

    @Test
    @DisplayName("组内只剩一条匹配时，权重必须退化成那一条而不是继续按权重掷骰")
    void onlyMatchingMemberGetsTheTraffic() {
        RouteMatcher m = matcher(
                route("a", "/a/**", "g,90", 1),
                route("b", "/b/**", "g,10", 2));

        Map<String, Integer> counts = tally(m, "/b/y");
        assertEquals(ROUNDS, counts.getOrDefault("b", 0), "全是 /b 请求，只能全给 b，实际 " + counts);
        assertEquals(0, counts.getOrDefault("a", 0), "a 的 path 不该收到 /b 的请求");
    }

    @Test
    @DisplayName("Weight 谓词本身永远放行（对照组：它只提供权重，不参与匹配判断）")
    void weightPredicateItselfAlwaysMatches() {
        GatewayContext c = ctx("/x", "r");
        assertTrue(new com.zifang.z.gw.core.predicate.WeightPredicateFactory()
                        .apply(c, PredicateDefinition.of("Weight", "g,90").getArgs()).isMatched(),
                "Weight 只提供权重，实际放行由 RouteMatcher 协调");
    }

    @Test
    @DisplayName("非灰度路由的匹配不受影响（对照组）")
    void plainRouteStillMatches() {
        RouteMatcher m = matcher(
                RouteDefinition.builder().id("plain").uri("http://plain").order(1)
                        .predicates(Arrays.asList(PredicateDefinition.of("Path", "/plain/**")))
                        .build());
        List<RouteDefinition> routes = m.currentRoutes();
        assertEquals(1, routes.size());
        assertEquals("plain", m.route(ctx("/plain/a", "r")).getId());
        assertEquals(null, m.route(ctx("/other", "r")), "非灰度路由不该被 Weight 逻辑卷进来");
    }
}
