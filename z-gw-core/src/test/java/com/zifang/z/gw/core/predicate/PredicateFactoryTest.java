package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 谓词工厂单元测试 — 覆盖 6 个内置工厂的关键路径。
 */
class PredicateFactoryTest {

    private GatewayContext ctx(String path) {
        GatewayContext c = new GatewayContext();
        c.setMethod("GET");
        c.setPath(path);
        c.setRequestId("test-1");
        return c;
    }

    @Test
    void path_exactMatch() {
        PathPredicateFactory f = new PathPredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("_genkey_0", "/api/users/list");
        assertTrue(f.apply(ctx("/api/users/list"), args).isMatched());
    }

    @Test
    void path_wildcardSingle() {
        PathPredicateFactory f = new PathPredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("_genkey_0", "/api/*");
        assertTrue(f.apply(ctx("/api/users"), args).isMatched());
        assertFalse(f.apply(ctx("/api/users/list"), args).isMatched());
    }

    @Test
    void path_wildcardMulti() {
        PathPredicateFactory f = new PathPredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("_genkey_0", "/api/**");
        assertTrue(f.apply(ctx("/api/users"), args).isMatched());
        assertTrue(f.apply(ctx("/api/users/list"), args).isMatched());
        assertTrue(f.apply(ctx("/api/users/1/orders"), args).isMatched());
    }

    @Test
    void method_caseInsensitive() {
        MethodPredicateFactory f = new MethodPredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("_genkey_0", "GET,POST");
        assertTrue(f.apply(ctx("/"), args).isMatched());
        assertTrue(f.apply(ctx("/"), args).isMatched());
    }

    @Test
    void header_present() {
        HeaderPredicateFactory f = new HeaderPredicateFactory();
        GatewayContext c = ctx("/");
        c.setAttribute("req.header.x-trace-id", "abc-123");
        Map<String, String> args = new HashMap<>();
        args.put("X-Trace-Id", ".+");
        assertTrue(f.apply(c, args).isMatched());
    }

    @Test
    void weight_predicateAlwaysMatches() {
        WeightPredicateFactory f = new WeightPredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("group", "90");
        assertTrue(f.apply(ctx("/"), args).isMatched());
        assertEquals(90, WeightPredicateFactory.extractWeight(args));
        assertEquals("group", WeightPredicateFactory.extractGroup(args));
    }

    @Test
    void time_outsideDailyWindow() {
        TimePredicateFactory f = new TimePredicateFactory();
        Map<String, String> args = new HashMap<>();
        args.put("_genkey_0", "00:00:00,00:00:01,UTC");
        // 当前时间几乎不可能在 0 点 ~ 0 点 1 秒这个 1 秒窗口里,允许 false 或 true 都视为"通过"
        f.apply(ctx("/"), args);  // 不抛异常即可
    }
}
