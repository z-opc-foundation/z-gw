package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GlobalFilter;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code clientIp} 不能由客户端自选。
 *
 * <p>原来的实现是 {@code xff.split(",")[0].trim()} —— 无条件采信 {@code X-Forwarded-For}
 * 的<b>最左</b>段，而且<b>没有任何开关能关</b>。最左段是整条链路上约束力最弱的一段：
 * 任何能连到网关的客户端自己填一个 {@code X-Forwarded-For: 6.6.6.6} 就能拿到这个值。
 * 而 {@code clientIp} 在本仓有四个出口：</p>
 *
 * <ul>
 *   <li>{@code RateLimitFilterFactory} 的默认限流键 → <b>IP 限流形同虚设</b>；</li>
 *   <li>{@code IpHashLoadBalancer} 的 hash 键 → 所有伪造同一 IP 的流量挤到同一个后端；</li>
 *   <li>{@code BackendHttpClient} 原样写进转发给后端的 {@code X-Forwarded-For}
 *       → <b>把不受控字符串注入内网请求头</b>；</li>
 *   <li>{@code LoggingGlobalFilter} 写访问日志。</li>
 * </ul>
 *
 * <p>现在：{@code trustedProxyHops} 默认 0（完全不采信请求头），设成 N 时才从 XFF
 * <b>右往左</b>数第 N 段。</p>
 */
class GatewayClientIpResolutionTest {

    private static final String PEER = "203.0.113.9";
    private static final SocketAddress REMOTE_V4 = new InetSocketAddress(PEER, 44444);

    private static FullHttpRequest req(String xff, String xri) {
        FullHttpRequest r = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/orders");
        if (xff != null) {
            r.headers().set("X-Forwarded-For", xff);
        }
        if (xri != null) {
            r.headers().set("X-Real-IP", xri);
        }
        return r;
    }

    private static String resolve(String xff, String xri, int hops) {
        return GatewayHandler.resolveClientIp(req(xff, xri), hops, REMOTE_V4);
    }

    // ==================================================================
    // 默认可信度为 0：客户端填的头一律不看
    // ==================================================================

    @Test
    @DisplayName("默认不采信 X-Forwarded-For（客户端自填的 IP 作废）")
    void defaultIgnoresSpoofedForwardedFor() {
        assertEquals(PEER, resolve("6.6.6.6", null, 0),
                "trustedProxyHops=0 时 clientIp 只能是 TCP 远端地址；"
                        + "采信最左段等于让调用方自选限流键");
    }

    @Test
    @DisplayName("默认不采信 X-Real-IP")
    void defaultIgnoresRealIp() {
        assertEquals(PEER, resolve(null, "6.6.6.6", 0));
    }

    // ==================================================================
    // 从右往左数：越靠右越接近网关、越可信
    // ==================================================================

    @Test
    @DisplayName("1 跳可信代理：取 XFF 最右段")
    void oneHopTakesRightmostSegment() {
        assertEquals("6.6.6.6", resolve("6.6.6.6", null, 1),
                "一跳代理时最右段就是它看到的真实来源");
    }

    @Test
    @DisplayName("1 跳：客户端伪造的左侧段必须被无视")
    void oneHopIgnoresSpoofedLeftSegment() {
        // 真实链路是 6.6.6.6 → 10.0.0.1(代理) → 网关，XFF 应当是 "6.6.6.6"。
        // 客户端在代理之前再插一段 "1.1.1.1"，从左数会取到它。
        assertEquals("6.6.6.6", resolve("1.1.1.1, 6.6.6.6", null, 1),
                "取的是从右数第 1 段；取最左段的话这里会变成 1.1.1.1");
    }

    @Test
    @DisplayName("2 跳可信代理：取 XFF 从右数第 2 段")
    void twoHopsTakesSecondFromRight() {
        // C=8.8.8.8 → P1=10.0.0.1 → P2=10.0.0.2 → 网关。
        // 按 proxy_add_x_forwarded_for：P1 转发时发 "8.8.8.8"，P2 转发时发 "8.8.8.8, 10.0.0.1"。
        // 注意 P2 自己是 TCP remote，**不进 XFF** —— 所以 XFF 段数 = 代理层数，不是 +1。
        // 客户端在 P1 之前伪造的那段留在最左边。
        assertEquals("8.8.8.8", resolve("9.9.9.9, 8.8.8.8, 10.0.0.1", null, 2),
                "每一跳代理把自己看到的来源 append 到链尾，所以要从右往左数");
    }

    @Test
    @DisplayName("3 跳：客户端伪造的前缀加了几段都不影响（从右数第 3 段）")
    void threeHopsWithExtraSpoofedPrefix() {
        // C=8.8.8.8 → P1 → P2 → P3 → 网关，XFF = "8.8.8.8, 10.0.0.1, 10.0.0.2"
        assertEquals("8.8.8.8", resolve("1.1.1.1, 2.2.2.2, 8.8.8.8, 10.0.0.1, 10.0.0.2", null, 3));
    }

    // ==================================================================
    // 取不到就回落：任何情况下都不把不受控的值放行
    // ==================================================================

    @Test
    @DisplayName("XFF 段数少于可信跳数：回落到 TCP 远端地址")
    void shorterChainFallsBackToRemote() {
        assertEquals(PEER, resolve("6.6.6.6", null, 2),
                "配了 2 跳却只收到 1 段，说明有跳没登记或有人在直连，按不可信处理");
    }

    @Test
    @DisplayName("目标段不是合法 IP：回落到 TCP 远端地址")
    void nonIpSegmentFallsBackToRemote() {
        assertEquals(PEER, resolve("6.6.6.6, not-an-ip", null, 1),
                "clientIp 会进日志、限流键和转发给后端的头，不能放任意字符串进去");
    }

    @Test
    @DisplayName("hops>1 时不拿 X-Real-IP 顶替第 N 跳")
    void realIpOnlyCountsForOneHop() {
        // X-Real-IP 只能代表"最靠近网关那一跳看到的来源"（= 第 1 跳）。
        // 配了 2 跳就拿它当第 2 跳，等于把内网代理的地址当成客户端 IP。
        assertEquals(PEER, resolve(null, "10.0.0.1", 2));
    }

    @Test
    @DisplayName("hops=1 且只有 X-Real-IP 时用它")
    void realIpUsedWhenOneHopAndNoXff() {
        assertEquals("6.6.6.6", resolve(null, "6.6.6.6", 1));
    }

    @Test
    @DisplayName("任何头都没有：回落到 TCP 远端地址")
    void noHeadersFallsBackToRemote() {
        assertEquals(PEER, resolve(null, null, 1));
    }

    // ==================================================================
    // 对照组：IPv6 远端地址
    // ==================================================================

    @Test
    @DisplayName("IPv6 远端地址取出的是 IP 本身（原先算出的是单个 \"[\"）")
    void ipv6RemoteIsTakenLiterally() {
        SocketAddress v6 = new InetSocketAddress("::1", 9090);
        String ip = GatewayHandler.resolveClientIp(req(null, null), 0, v6);

        assertNotEquals("[", ip,
                "原实现是 toString() 后\"截断到第一个冒号\"，IPv6 下算出的是单个 \"[\" —— "
                        + "限流按它分组时所有 IPv6 客户端挤成一组");
        assertEquals("0:0:0:0:0:0:0:1", ip,
                "应取 InetAddress.getHostAddress()，实际 " + ip);
    }

    // ==================================================================
    // 端到端：真的进到了 GatewayContext（否则上面那些只测了 helper）
    // ==================================================================

    @Test
    @DisplayName("端到端：进 GatewayContext 的 clientIp 也不是客户端自填的那个")
    void contextCarriesPeerAddressNotSpoofedHeader() throws Exception {
        ServerConfig cfg = new ServerConfig();
        assertEquals(0, cfg.getTrustedProxyHops(), "可信代理跳数的默认值必须是 0（不采信任何请求头）");

        AtomicReference<GatewayContext> seen = new AtomicReference<>();
        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new GlobalFilter() {
            @Override
            public String name() {
                return "ctx-capture";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public void filter(GatewayContext ctx, GatewayFilterChain chain) {
                seen.set(ctx);
                chain.filter(ctx);
            }
        });        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/api").order(1).build()));
        EmbeddedChannel ch = new EmbeddedChannel(new GatewayHandler(cfg, matcher, assembler, null));

        FullHttpRequest r = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/x");
        r.headers().set("X-Forwarded-For", "6.6.6.6");
        ch.writeInbound(r);
        long deadline = System.currentTimeMillis() + 3000;
        while (seen.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        GatewayContext ctx = seen.get();
        assertNotNull(ctx, "过滤器链没跑到，夹具有问题");
        assertNotEquals("6.6.6.6", ctx.getClientIp(),
                "客户端自填的 X-Forwarded-For 成了 GatewayContext.clientIp —— "
                        + "它会一路流到限流键、IP hash 与转发给后端的头");
        assertNotNull(ctx.getClientIp());
        assertTrue(ctx.getClientIp().length() > 0, "clientIp 不该是空串（空串会让所有匿名请求挤成一组限流）");
    }
}
