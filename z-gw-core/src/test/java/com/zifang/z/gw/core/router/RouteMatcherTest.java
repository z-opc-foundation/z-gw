package com.zifang.z.gw.core.router;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.api.FilterDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路由匹配单元测试。
 */
class RouteMatcherTest {

    private GatewayContext ctx(String path) {
        GatewayContext c = new GatewayContext();
        c.setMethod("GET");
        c.setPath(path);
        c.setRequestId("test-1");
        return c;
    }

    @Test
    void matchesFirstEnabledRoute() {
        RouteMatcher m = new RouteMatcher();
        RouteDefinition r1 = RouteDefinition.builder()
                .id("r1").uri("http://a")
                .order(1)
                .predicates(Arrays.asList(PredicateDefinition.of("Path", "/api/a/**")))
                .build();
        RouteDefinition r2 = RouteDefinition.builder()
                .id("r2").uri("http://b")
                .order(2)
                .predicates(Arrays.asList(PredicateDefinition.of("Path", "/api/b/**")))
                .build();
        m.refresh(Arrays.asList(r1, r2));

        assertEquals("r1", m.route(ctx("/api/a/x")).getId());
        assertEquals("r2", m.route(ctx("/api/b/y")).getId());
        assertNull(m.route(ctx("/unknown")));
    }

    @Test
    void disabledRoutesAreSkipped() {
        RouteMatcher m = new RouteMatcher();
        RouteDefinition r1 = RouteDefinition.builder()
                .id("r1").uri("http://a").order(1)
                .enabled(false)
                .predicates(Arrays.asList(PredicateDefinition.of("Path", "/api/a/**")))
                .build();
        m.refresh(Arrays.asList(r1));
        assertNull(m.route(ctx("/api/a/x")));
    }

    @Test
    void orderedByOrderThenId() {
        RouteMatcher m = new RouteMatcher();
        RouteDefinition r1 = RouteDefinition.builder()
                .id("a-route").uri("http://a").order(2)
                .predicates(Arrays.asList(PredicateDefinition.of("Path", "/x")))
                .build();
        RouteDefinition r2 = RouteDefinition.builder()
                .id("b-route").uri("http://b").order(1)
                .predicates(Arrays.asList(PredicateDefinition.of("Path", "/x")))
                .build();
        m.refresh(Arrays.asList(r1, r2));
        assertEquals("b-route", m.route(ctx("/x")).getId());
    }

    @Test
    void multiplePredicatesAllMustMatch() {
        RouteMatcher m = new RouteMatcher();
        RouteDefinition r1 = RouteDefinition.builder()
                .id("r1").uri("http://a").order(1)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", "/api/**"),
                        PredicateDefinition.of("Method", "GET,POST")
                ))
                .build();
        m.refresh(Arrays.asList(r1));

        assertNotNull(m.route(ctx("/api/x")));
    }

    @Test
    void noMatchReturnsNull() {
        RouteMatcher m = new RouteMatcher();
        m.refresh(java.util.Collections.<RouteDefinition>emptyList());
        assertNull(m.route(ctx("/anywhere")));
    }

    @Test
    void weightRoute_deterministic() {
        RouteMatcher m = new RouteMatcher();
        RouteDefinition r1 = RouteDefinition.builder()
                .id("v1").uri("http://v1").order(1)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", "/x"),
                        PredicateDefinition.of("Weight", "g,90")
                ))
                .build();
        RouteDefinition r2 = RouteDefinition.builder()
                .id("v2").uri("http://v2").order(2)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", "/x"),
                        PredicateDefinition.of("Weight", "g,10")
                ))
                .build();
        m.refresh(Arrays.asList(r1, r2));

        // 随机 100 次,大权重应该被选中更多次
        int v1Count = 0;
        int v2Count = 0;
        for (int i = 0; i < 100; i++) {
            // 每次新建 context,requestId 随机所以选路也是随机的
            GatewayContext c = ctx("/x");
            c.setRequestId("req-" + i);
            RouteDefinition m1 = m.route(c);
            if (m1 != null && m1.getId().equals("v1")) v1Count++;
            if (m1 != null && m1.getId().equals("v2")) v2Count++;
        }
        assertEquals(100, v1Count + v2Count);
        assertTrue(v1Count > v2Count, "v1 应被选中更多次,实测 v1=" + v1Count + " v2=" + v2Count);
    }
}
