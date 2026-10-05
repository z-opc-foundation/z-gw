package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.PredicateResult;
import com.zifang.z.gw.core.filter.factory.AddRequestHeaderFilterFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 谓词的 args 有两种写法，工厂必须都认。
 *
 * <p>{@code PredicateDefinition.of(name, singleArg)} 把整串收成
 * {@code {"_genkey_0": singleArg}} —— key 是定长占位符（SCG 的写法），
 * 真正的参数全在 value 里。yml 简写语法走的正是这条路径
 * （{@code YamlRouteLoader.parsePredicates} 的 {@code "Path=/api/**"} 分支）。</p>
 *
 * <p>本仓的过滤器工厂 {@code AddRequestHeaderFilterFactory} /
 * {@code AddResponseHeaderFilterFactory} 已经按这个约定解析；而
 * {@link WeightPredicateFactory} 与 {@link HeaderPredicateFactory} 没有 ——
 * 它们把 key 当成语义值，于是简写出来的 group / header 名恒为 {@code _genkey_0}。
 * 同一份 args 契约、同一个仓，两种实现，用对的那一处证伪错的那一处。</p>
 */
class PredicateShorthandArgTest {

    private static GatewayContext ctxWithHeader(String name, String value) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setPath("/x");
        // GatewayHandler 两个键都写：原样大小写 + 全小写
        ctx.setAttribute("req.header." + name, value);
        ctx.setAttribute("req.header." + name.toLowerCase(), value);
        return ctx;
    }

    private static Map<String, String> mapForm(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // === Header ===

    @Test
    @DisplayName("Header 简写 `Header=X-Trace-Id,.+` 必须按 X-Trace-Id 去取头")
    void headerShorthandNamesTheHeaderItLooksUp() {
        GatewayContext ctx = ctxWithHeader("X-Trace-Id", "abc-123");
        PredicateResult r = new HeaderPredicateFactory().apply(
                ctx, PredicateDefinition.of("Header", "X-Trace-Id,.+").getArgs());

        assertTrue(r.isMatched(),
                "简写写法的 header 名必须从 value 逗号左边解析出来；未修实现会去查 "
                        + "req.header._genkey_0（" + r + "）");
    }

    @Test
    @DisplayName("Header 简写的值正则要真的生效（`Header=X-Trace-Id,abc-.*`）")
    void headerShorthandHonoursTheValueRegex() {
        // 注意用 abc-.* 而不是 ^abc-：String.matches 要求整串匹配完，^abc- 消费不完 "abc-123"
        PredicateDefinition pd = PredicateDefinition.of("Header", "X-Trace-Id,abc-.*");
        assertTrue(new HeaderPredicateFactory()
                        .apply(ctxWithHeader("X-Trace-Id", "abc-123"), pd.getArgs()).isMatched(),
                "abc-.* 应命中 abc-123");
        assertTrue(new HeaderPredicateFactory()
                        .apply(ctxWithHeader("X-Trace-Id", "xyz-999"), pd.getArgs()).isNotMatched(),
                "abc-.* 不该命中 xyz-999");
    }

    @Test
    @DisplayName("Header 的 map 写法照常工作（对照组：证明改动没把这条路写坏）")
    void headerMapFormStillWorks() {
        GatewayContext ctx = ctxWithHeader("X-Trace-Id", "abc-123");
        PredicateResult r = new HeaderPredicateFactory().apply(
                ctx, PredicateDefinition.of("Header", mapForm("X-Trace-Id", ".+")).getArgs());
        assertTrue(r.isMatched(), "map 写法 key 就是头名，不该受影响：" + r);
    }

    // === Weight ===

    @Test
    @DisplayName("Weight 简写 `Weight=user_group,90` 必须解析出 group=user_group / weight=90")
    void weightShorthandIsParsedAsGroupAndWeight() {
        Map<String, String> args = PredicateDefinition.of("Weight", "user_group,90").getArgs();
        assertEquals("user_group", WeightPredicateFactory.extractGroup(args),
                "group 必须取 value 逗号左边；未修实现返回 key 本身，实际 "
                        + WeightPredicateFactory.extractGroup(args));
        assertEquals(90, WeightPredicateFactory.extractWeight(args),
                "weight 必须取 value 逗号右边；未修实现对 \"user_group,90\" 做 parseInt 直接"
                        + "NumberFormatException 吞成 0，于是 selectByWeight 的 total 恒为 0、"
                        + "灰度 100% 打到组内第一条");
    }

    @Test
    @DisplayName("Weight 的 map 写法照常工作（对照组）")
    void weightMapFormStillWorks() {
        Map<String, String> args = PredicateDefinition.of("Weight", mapForm("g", "90")).getArgs();
        assertEquals("g", WeightPredicateFactory.extractGroup(args), "map 写法 key 就是 group");
        assertEquals(90, WeightPredicateFactory.extractWeight(args), "map 写法 value 就是 weight");
    }

    @Test
    @DisplayName("简写里缺 weight（`Weight=user_group`）不得当成 0 之外的怪值，按 0 处理")
    void weightShorthandWithoutWeight() {
        Map<String, String> args = PredicateDefinition.of("Weight", "user_group").getArgs();
        assertEquals("user_group", WeightPredicateFactory.extractGroup(args), "group 仍要取对");
        assertEquals(0, WeightPredicateFactory.extractWeight(args), "缺 weight 时按 0");
    }

    // === 对照组：同仓已有的正确实现 ===

    @Test
    @DisplayName("对照组：AddRequestHeader 过滤器早就正确处理了 _genkey_0 简写")
    void addRequestHeaderShorthandAlreadyWorks() {
        Map<String, String> reqHeaders = new HashMap<>();
        GatewayContext ctx = new GatewayContext();
        ctx.setAttribute("req.headers", reqHeaders);

        GatewayFilter f = new AddRequestHeaderFilterFactory().apply(
                PredicateDefinition.of("AddRequestHeader", "X-Trace-Id, z-gw").getArgs());
        f.filter(ctx, c -> { });

        assertEquals("z-gw", reqHeaders.get("X-Trace-Id"),
                "同仓的过滤器工厂按 _genkey_0 约定解析，谓词工厂照此办理即可");
    }
}
