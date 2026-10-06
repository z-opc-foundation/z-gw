package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.core.config.CorsPolicy;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.router.RouteMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 策略由 {@code zgw.server.cors-*} 五个字段真正决定，且<b>默认关闭</b>。
 *
 * <p>这一层钉的是三件事：</p>
 * <ol>
 *   <li><b>预检与实际响应必须说同一套话</b>（下面"已开启"那组）。按 CORS 规范，浏览器读的是
 *       <b>实际响应</b>上的那一个头，预检通过并不算数。此前预检声明了 {@code ACAO: *} 而
 *       全仓只出现在那一处，症状极具迷惑性：Network 面板里预检 204 + 正式请求 200 都正常，
 *       控制台却报 CORS 错误。</li>
 *   <li><b>默认关闭时一个 CORS 头都不写</b>，且 OPTIONS 不被网关吞掉。
 *       {@code corsEnabled} 此前零读取点、而实际响应头无条件写 {@code *}，
 *       所以"默认关闭跨域"这个声明从来没生效过。</li>
 *   <li><b>另外四个字段不再是死参数</b>：{@code corsAllowedMethods} /
 *       {@code corsAllowedHeaders} / {@code corsMaxAge} / {@code corsAllowedOrigins}
 *       此前全无读取点，策略一律写死。</li>
 * </ol>
 */
class GatewayHandlerCorsTest {

    private static final String ORIGIN = "http://app.example.com";

    private static ServerConfig corsOn() {
        ServerConfig cfg = new ServerConfig();
        cfg.setCorsEnabled(true);
        return cfg;
    }

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(new GatewayHandler(corsOn(), new RouteMatcher(), null, null));
    }

    /** 默认配置：{@code corsEnabled} 就是 false。 */
    private static EmbeddedChannel newDefaultChannel() {
        return new EmbeddedChannel(new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
    }

    private static FullHttpRequest request(String method, String uri) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.valueOf(method), uri);
        req.headers().set(HttpHeaderNames.ORIGIN, ORIGIN);
        return req;
    }

    private static String allowOrigin(FullHttpResponse resp) {
        return resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    /**
     * 造一个带真实 {@link ChannelHandlerContext} 的通道。
     * <p>
     * {@code new EmbeddedChannel()} 的 pipeline 是空的，{@code firstContext()} 返回 null，
     * 直接拿它调 {@code writeAndFlush} 会 NPE（那是夹具坏了，不是被测代码坏了）——
     * 所以塞一个空 handler 进 pipeline，{@code firstContext()} 才有东西可返回。
     */
    private static EmbeddedChannel newWritableChannel() {
        return new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
    }

    private static ChannelHandlerContext ctxOf(EmbeddedChannel ch) {
        return ch.pipeline().firstContext();
    }

    // ==================================================================
    // 对照组：预检本来就带这个头（钉住"网关已经声明过这个策略"）
    // ==================================================================

    @Test
    @DisplayName("对照组：预检响应带 ACAO —— 网关对外声明的就是这个策略")
    void preflightCarriesCorsHeaders() {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(request("OPTIONS", "/anything"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NO_CONTENT, resp.status());
        assertEquals("*", allowOrigin(resp),
                "预检本来就带这个头，修复前后都应如此");
    }

    // ==================================================================
    // 反向：实际响应一个都没有（修复前全红）
    // ==================================================================

    @Test
    @DisplayName("未命中路由的 404 响应必须带 ACAO")
    void notFoundResponseCarriesCorsHeaders() {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(request("GET", "/no-such-route"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        assertEquals("*", allowOrigin(resp),
                "404 也是浏览器要读的实际响应；修复前这一格是 null");
    }

    @Test
    @DisplayName("健康检查响应必须带 ACAO")
    void healthResponseCarriesCorsHeaders() {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(request("GET", "/health"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("*", allowOrigin(resp),
                "修复前这一格是 null");
    }

    /**
     * 最要紧的一格：<b>代理转发回来的业务响应</b>。
     * <p>
     * {@code writeFullResponse} 是 {@code public static}，直接拿 {@link EmbeddedChannel}
     * 的 pipeline 上下文调它即可，不需要真后端。
     */
    @Test
    @DisplayName("代理回来的业务响应必须带 ACAO（预检有、实际没有的那个矛盾）")
    void proxiedBackendResponseCarriesCorsHeaders() {
        EmbeddedChannel ch = newWritableChannel();
        ChannelHandlerContext nettyCtx = ctxOf(ch);

        FullHttpResponse backend = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        backend.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");

        GatewayHandler.writeFullResponse(nettyCtx, request("GET", "/api/orders"), backend, null,
                CorsPolicy.from(corsOn()));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("*", allowOrigin(resp),
                "预检已经声明 ACAO: *，而真正的业务响应却没有 —— 浏览器会在这里拦掉响应体");
    }

    @Test
    @DisplayName("错误响应（401/403/429/5xx 那一族）必须带 ACAO")
    void gatewayErrorResponseCarriesCorsHeaders() {
        EmbeddedChannel ch = newWritableChannel();
        ChannelHandlerContext nettyCtx = ctxOf(ch);

        GatewayHandler.writeError(nettyCtx, request("GET", "/api/orders"),
                new GatewayException.UnauthorizedException("missing token"), CorsPolicy.from(corsOn()));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, resp.status());
        assertEquals("*", allowOrigin(resp),
                "前端最常遇到的就是 401/403；缺这个头的话连错误信息都读不到");
    }

    @Test
    @DisplayName("兜底 500 响应必须带 ACAO")
    void internalErrorResponseCarriesCorsHeaders() {
        EmbeddedChannel ch = newWritableChannel();
        ChannelHandlerContext nettyCtx = ctxOf(ch);

        GatewayHandler.writeInternalError(nettyCtx, request("GET", "/api/orders"),
                "req-1", new IllegalStateException("boom"), CorsPolicy.from(corsOn()));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status());
        assertEquals("*", allowOrigin(resp), "修复前这一格是 null");
    }

    /**
     * 反向：<b>后端自己给了 ACAO 时，网关不许覆盖</b>。
     * <p>
     * {@code writeFullResponse} 有一行 {@code client.headers().set(backendResp.headers())}
     * 会把后端的头整个搬过来。若网关无条件 {@code set(ACAO, "*")}，
     * 那个服务自己声明的跨域策略就被悄悄改掉了——而按规范，
     * <b>实际响应上那个头才是权威</b>（预检那份不是）。所以用 {@code contains} 判缺省。
     */
    @Test
    @DisplayName("后端已显式设置 ACAO 时必须原样保留")
    void backendSuppliedAllowOriginIsNotOverwritten() {
        EmbeddedChannel ch = newWritableChannel();
        ChannelHandlerContext nettyCtx = ctxOf(ch);

        FullHttpResponse backend = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        backend.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "http://only-this.example.com");

        GatewayHandler.writeFullResponse(nettyCtx, request("GET", "/api/orders"), backend, null,
                CorsPolicy.from(corsOn()));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("http://only-this.example.com", allowOrigin(resp),
                "后端显式声明的跨域策略是那个服务的选择，网关不该改成 *");
    }

    // ==================================================================
    // 默认关闭：corsEnabled 是 false 时一个 CORS 头都不写
    // ==================================================================

    @Test
    @DisplayName("默认配置下健康检查不带任何 CORS 头")
    void corsHeadersAreAbsentByDefault() {
        EmbeddedChannel ch = newDefaultChannel();
        ch.writeInbound(request("GET", "/health"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "corsEnabled 默认是 false，此前这一格被无条件写成 *，等于『默认关闭跨域』从未生效");
    }

    @Test
    @DisplayName("默认配置下 OPTIONS 不再被网关吞掉（走正常的路由匹配）")
    void optionsIsNotSwallowedByDefault() {
        EmbeddedChannel ch = newDefaultChannel();
        ch.writeInbound(request("OPTIONS", "/anything"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status(),
                "跨域没开时预检不该由网关代答；此前无论开关如何，OPTIONS 一律被 writeCorsOptions 拦成 204");
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ==================================================================
    // 另外四个字段此前零读取点
    // ==================================================================

    @Test
    @DisplayName("corsAllowedMethods / corsAllowedHeaders / corsMaxAge 真接了（此前一律写死）")
    void preflightHonoursConfiguredMethodsHeadersMaxAge() {
        ServerConfig cfg = corsOn();
        cfg.setCorsAllowedMethods("GET,POST");
        cfg.setCorsAllowedHeaders("X-Trace-Id,Content-Type");
        cfg.setCorsMaxAge(7200);
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(cfg, new RouteMatcher(), null, null));
        ch.writeInbound(request("OPTIONS", "/api/orders"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("GET,POST", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS),
                "此前写死 GET,POST,PUT,DELETE,OPTIONS,PATCH，配置一律无效");
        assertEquals("X-Trace-Id,Content-Type",
                resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("7200", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE),
                "此前写死 3600，配置一律无效");
    }

    @Test
    @DisplayName("corsAllowedOrigins 配成白名单：命中的回显，且带 Vary: Origin")
    void allowOriginWhitelistEchoesMatchWithVary() {
        ServerConfig cfg = corsOn();
        // 白名单里必须真的含 ORIGIN，否则这条钉的是"不在白名单被拒"，与下面的
        // allowOriginOutsideWhitelistIsRefused 重复
        cfg.setCorsAllowedOrigins("https://a.example.com, " + ORIGIN);
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(cfg, new RouteMatcher(), null, null));
        ch.writeInbound(request("OPTIONS", "/api/orders"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(ORIGIN, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "命中的 Origin 必须被回显 —— 写 * 在带凭据的场景下会被浏览器拒");
        String vary = resp.headers().get(HttpHeaderNames.VARY);
        assertNotNull(vary, "回显具体 Origin 时必须标 Vary，否则缓存会把这一份喂给别的站点");
        assertTrue(vary.contains("Origin"), "Vary 实际为: " + vary);
    }

    @Test
    @DisplayName("corsAllowedOrigins 白名单之外的来源：一个 ACAO 都不写")
    void allowOriginOutsideWhitelistIsRefused() {
        ServerConfig cfg = corsOn();
        cfg.setCorsAllowedOrigins("https://allowed.example.com");
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api/orders");
        req.headers().set(HttpHeaderNames.ORIGIN, "https://evil.example.com");
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(cfg, new RouteMatcher(), null, null));
        ch.writeInbound(req);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "不在白名单却回 ACAO: * —— 那正是本次要修掉的无条件放行");
    }

    @Test
    @DisplayName("corsAllowedOrigins 配 '*' 时回 *（对照组：白名单不是恒定拒绝）")
    void wildcardOriginStillWorks() {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(request("OPTIONS", "/anything"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("*", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("enabled 但 origins 一个都没配：不允许任何来源（不能因为配漏就全放行）")
    void emptyOriginListAllowsNothing() {
        ServerConfig cfg = corsOn();
        cfg.setCorsAllowedOrigins("");
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api/orders");
        req.headers().set(HttpHeaderNames.ORIGIN, ORIGIN);
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(cfg, new RouteMatcher(), null, null));
        ch.writeInbound(req);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "空列表的语义必须是『空』，而不是退化成 * —— 后者正是本次要修掉的方向");
    }

    /**
     * 同一不变式在<b>实际响应</b>出口上也成立。
     *
     * <p>这条和 {@link #allowOriginWhitelistEchoesMatchWithVary} 验的是同一件事
     * （回显具体 Origin 时必须带 {@code Vary: Origin}），但走的是
     * {@code applyActual} 而不是 {@code applyPreflight}。两个出口各自有一份实现，
     * 只断言其中一个的话，摘掉另一份的判据照样全绿 —— 本轮变异验证就是这么发现的。</p>
     */
    @Test
    @DisplayName("白名单回显在实际响应上也要带 Vary: Origin（与预检出口是两份实现）")
    void allowOriginWhitelistEchoesWithVaryOnActualResponse() {
        ServerConfig cfg = corsOn();
        cfg.setCorsAllowedOrigins("https://a.example.com, " + ORIGIN);

        EmbeddedChannel ch = newWritableChannel();
        ChannelHandlerContext nettyCtx = ctxOf(ch);
        FullHttpResponse backend = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer("{}".getBytes(StandardCharsets.UTF_8)));
        GatewayHandler.writeFullResponse(nettyCtx, request("GET", "/api/orders"), backend, null,
                CorsPolicy.from(cfg));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(ORIGIN, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        String vary = resp.headers().get(HttpHeaderNames.VARY);
        assertNotNull(vary, "实际响应回显具体 Origin 时必须标 Vary，实际无此头");
        assertTrue(vary.contains("Origin"), "Vary 实际为: " + vary);
    }

    /** {@code applyPreflight} 那道 enabled 闸门本身也钉一条，别只靠调用方拦。 */
    @Test
    @DisplayName("策略为关闭时 writeCorsOptions 一个 CORS 头都不写")
    void preflightWritesNothingWhenDisabled() {
        EmbeddedChannel ch = newWritableChannel();
        GatewayHandler.writeCorsOptions(ctxOf(ch), request("OPTIONS", "/api/orders"),
                CorsPolicy.DISABLED);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NO_CONTENT, resp.status());
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals(null, resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
    }
}
