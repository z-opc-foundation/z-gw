package com.zifang.z.gw.core.runtime;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.router.RouteMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由配置解析必须 fail-fast，不能把笔误变成"更宽松的路由"。
 *
 * <p>要害在 {@code RouteMatcher.matchesAllPredicates}：</p>
 * <pre>
 *   if (predicates == null || predicates.isEmpty()) return true;   // ← 空 = 匹配一切
 * </pre>
 *
 * <p>而 {@code parsePredicates} / {@code parseFilters} 对"没有 {@code =} 的简写条目"
 * 是<b>静默跳过</b>，一条日志都没有。于是配置里</p>
 * <pre>
 *   predicates: ["Path"]        // 手滑，漏了 "/api/**"
 * </pre>
 * <p>解析出的谓词列表是<b>空的</b> ⇒ 这条本该只管 {@code /api/**} 的路由
 * <b>匹配所有请求</b>。</p>
 *
 * <p>对照：<b>不认识</b>的谓词名会被 {@code log.warn("Unknown predicate factory…")}
 * 并 {@code return false}（拒绝匹配）——"名字不认识"是明确拒绝，
 * "名字认识但条目解析不出来"却静默放开，后者的危险大得多。</p>
 */
class YamlRouteLoaderStrictnessTest {

    private static GatewayContext ctx(String path) {
        GatewayContext c = new GatewayContext();
        c.setMethod("GET");
        c.setPath(path);
        c.setRequestId("t-1");
        return c;
    }

    private static List<RouteDefinition> parse(String json) {
        return SimpleJsonRouteParser.parse(json);
    }

    // ==================================================================
    // 谓词：解析不出来必须报错，不能静默丢弃
    // ==================================================================

    @Test
    @DisplayName("谓词条目缺 '=' 时必须失败，而不是让路由变宽")
    void predicateWithoutEqualsFailsFast() {
        String json = "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"predicates\":[\"Path\"]}]";
        // 断言消息而不只是类型：只写 assertThrows(RuntimeException.class) 区分不了
        // 「按设计 fail-fast」和「substring(0, -1) 抛的 StringIndexOutOfBoundsException」——
        // 后者也是 RuntimeException，测试照样绿。
        RuntimeException e = assertThrows(RuntimeException.class, () -> parse(json),
                "拼错的谓词被静默丢弃会让该路由匹配一切（predicates 为空 ⇒ matchesAllPredicates=true）");
        assertTrue(e.getMessage().contains("缺少 '='"),
                "应是明确的配置错误提示，而不是字符串越界: " + e.getMessage());
    }

    @Test
    @DisplayName("谓词条目 '=' 在首位也必须失败")
    void predicateWithLeadingEqualsFailsFast() {
        // eq > 0 的写法会跳过 "=/api/**"，等于丢一条限制条件
        String json = "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"predicates\":[\"=/api/**\"]}]";
        RuntimeException e = assertThrows(RuntimeException.class, () -> parse(json));
        assertTrue(e.getMessage().contains("缺少 '='"),
                "应是明确的配置错误提示，而不是字符串越界: " + e.getMessage());
    }

    @Test
    @DisplayName("谓词缺 name 也必须失败（name 为 null 会造出无法匹配的无效定义）")
    void predicateWithoutNameFailsFast() {
        String json = "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,"
                + "\"predicates\":[{\"args\":{\"pattern\":\"/api/**\"}}]}]";
        RuntimeException e = assertThrows(RuntimeException.class, () -> parse(json));
        assertTrue(e.getMessage().contains("predicate name"),
                "应由 PredicateDefinition 的 requireNonNull 报出: " + e.getMessage());
    }

    // ==================================================================
    // 过滤器同样
    // ==================================================================

    @Test
    @DisplayName("过滤器条目缺 '=' 时必须失败")
    void filterWithoutEqualsFailsFast() {
        String json = "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"filters\":[\"StripPrefix\"]}]";
        RuntimeException e = assertThrows(RuntimeException.class, () -> parse(json));
        assertTrue(e.getMessage().contains("缺少 '='"),
                "应是明确的配置错误提示，而不是字符串越界: " + e.getMessage());
    }

    @Test
    @DisplayName("过滤器缺 name 也必须失败")
    void filterWithoutNameFailsFast() {
        String json = "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,"
                + "\"filters\":[{\"args\":{\"n\":1}}]}]";
        RuntimeException e = assertThrows(RuntimeException.class, () -> parse(json));
        assertTrue(e.getMessage().contains("filter name"),
                "应由 FilterDefinition 的 requireNonNull 报出: " + e.getMessage());
    }

    // ==================================================================
    // 对照组：正常配置必须照常工作，且谓词真的在收紧匹配
    // ==================================================================

    @Test
    @DisplayName("简写谓词 \"Path=/api/**\" 照常解析（对照组）")
    void validShorthandPredicateStillParses() {
        List<RouteDefinition> routes = parse(
                "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"predicates\":[\"Path=/api/**\"]}]");

        assertEquals(1, routes.size());
        List<PredicateDefinition> ps = routes.get(0).getPredicates();
        assertNotNull(ps);
        assertEquals(1, ps.size());
        assertEquals("Path", ps.get(0).getName());
    }

    @Test
    @DisplayName("谓词真的在收紧匹配（对照组：证明空谓词=放开的对比）")
    void validPredicateActuallyNarrowsMatching() {
        RouteMatcher m = new RouteMatcher();
        m.refresh(parse("[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,"
                + "\"predicates\":[\"Path=/api/**\"]}]"));

        assertNotNull(m.route(ctx("/api/orders")), "/api/orders 应命中");
        assertEquals(null, m.route(ctx("/admin/secret")), "/admin/secret 不应命中");
    }

    @Test
    @DisplayName("map 形式与混合列表都照常解析（对照组）")
    void mapFormStillParses() {
        List<RouteDefinition> routes = parse(
                "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"predicates\":"
                        + "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/**\"}}, \"Method=GET\"]}]");

        List<PredicateDefinition> ps = routes.get(0).getPredicates();
        assertEquals(2, ps.size());
        assertEquals("Path", ps.get(0).getName());
        assertEquals("Method", ps.get(1).getName());
    }

    @Test
    @DisplayName("空 predicates 是显式配置而非笔误，不应被本次改动误伤（对照组）")
    void explicitlyEmptyPredicatesIsStillAllowed() {
        // 有些路由确实想"无条件匹配"，用空数组显式表达是合法的
        List<RouteDefinition> routes = parse(
                "[{\"id\":\"r1\",\"uri\":\"http://a\",\"order\":1,\"predicates\":[]}]");
        assertEquals(1, routes.size());
        assertTrue(routes.get(0).getPredicates() == null || routes.get(0).getPredicates().isEmpty());
    }
}
