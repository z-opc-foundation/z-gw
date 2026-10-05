package com.zifang.z.gw.core.filter;

import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.core.router.FilterAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 由 {@code GatewayHandler} 处理，不在过滤器链里 —— 这里钉住这个事实。
 *
 * <p>此前的 {@code CorsGlobalFilter} 是个可证明的 no-op：</p>
 * <ul>
 *   <li>它唯一会设的 attribute 是 {@code cors.short.circuit}，全仓 grep 只有那一行写入、
 *       <b>零读取</b>；</li>
 *   <li>那段逻辑在 {@code if ("OPTIONS".equalsIgnoreCase(...))} 里，而 OPTIONS 早在
 *       {@code GatewayHandler.channelRead0} 就被拦下 {@code writeCorsOptions} 直接返回，
 *       <b>根本进不了过滤器链</b>；</li>
 *   <li>非 OPTIONS 走进来时两个分支都不命中，最后无条件 {@code chain.filter(ctx)}，
 *       <b>一个响应头都不设</b>；</li>
 *   <li>它的 Javadoc 写着「自动添加 CORS 响应头」「对所有路径生效」，两句话都不成立。</li>
 * </ul>
 *
 * <p>留着它的害处不在于多跑一次，而在于 {@code FilterChainBootstrap} 里那段
 * 「=== 内置全局过滤器 ===」会让读代码的人以为 CORS 是走链处理的。CORS 的实际实现在
 * {@code GatewayHandler.writeCorsOptions} / {@code writeFullResponse} / {@code writeJson}
 * 三处，已有 {@code GatewayHandlerCorsTest} 7 道用例覆盖。</p>
 */
class FilterChainBootstrapDefaultsTest {

    private static List<GatewayFilter> installDefaults() {
        FilterAssembler assembler = new FilterAssembler();
        new FilterChainBootstrap(assembler, new GatewayFilterFactoryRegistry()).installDefaults();
        return assembler.getGlobalFilters();
    }

    @Test
    @DisplayName("默认全局链里不该再有那个什么都不做的 CORS 过滤器")
    void noInertCorsFilterInTheDefaultChain() {
        List<String> names = installDefaults().stream()
                .map(GatewayFilter::name).collect(Collectors.toList());

        assertFalse(names.contains("Cors"),
                "CORS 由 GatewayHandler 在进链之前/写回时处理，链里不该再有一个只 setAttribute"
                        + "（cors.short.circuit 全仓零读取）且不设任何头、也不短路的空转过滤器；"
                        + "当前默认链: " + names);
    }

    @Test
    @DisplayName("对照组：其余内置全局过滤器一个都不能少（证明上一条不是靠少装过滤器过的）")
    void otherGlobalFiltersAreStillInstalled() {
        List<String> names = installDefaults().stream()
                .map(GatewayFilter::name).collect(Collectors.toList());

        for (String expected : new String[]{"Tracing", "Metrics", "Logging", "ErrorHandling", "NettyProxy"}) {
            assertTrue(names.contains(expected),
                    "默认链应仍包含 " + expected + "，实际: " + names);
        }
        assertEquals(5, names.size(), "默认全局过滤器应是 5 个，实际: " + names);
    }

    @Test
    @DisplayName("对照组：默认链仍按 order 升序（Tracing 最先、NettyProxy 最后）")
    void globalFiltersStayOrdered() {
        List<Integer> orders = installDefaults().stream()
                .map(GatewayFilter::order).collect(Collectors.toList());

        assertEquals(orders.stream().sorted().collect(Collectors.toList()), orders,
                "全局过滤器必须按 order 升序，实际: " + orders);
        assertEquals(-1000, orders.get(0), "Tracing(order=-1000) 应最先");
        assertEquals(999, orders.get(orders.size() - 1), "NettyProxy(order=999) 应最后");
    }
}
