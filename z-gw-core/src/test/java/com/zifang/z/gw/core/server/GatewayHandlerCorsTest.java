package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.GatewayException;
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

/**
 * 实际响应必须和预检响应一样带 {@code Access-Control-Allow-Origin}。
 *
 * <p>这一层钉的是一个<b>自相矛盾</b>，不是"网关该不该开 CORS"：网关自己的预检响应
 * （{@link GatewayHandler#writeCorsOptions}）已经声明了 {@code ACAO: *}，
 * 而全仓 {@code ACCESS_CONTROL_ALLOW_ORIGIN} 只出现在那一处——
 * 真正回给浏览器的业务响应（{@code writeFullResponse}）与全部错误/JSON 响应
 * （{@code writeJson}，{@code writeError} / {@code writeInternalError} 都汇入它）一个都没有。</p>
 *
 * <p>按 CORS 规范，浏览器要读的是<b>实际响应</b>上的那一个头，预检通过并不算数。
 * 于是症状极具迷惑性：Network 面板里预检 204 + 正式请求 200 都正常，
 * 控制台却报 CORS 错误，页面上拿到的是"请求失败"。
 * 而 {@code ACAO: *} 且不带 {@code Access-Control-Allow-Credentials} 时
 * 浏览器不会带上 cookie，所以补齐这个头不构成凭据泄露面。</p>
 */
class GatewayHandlerCorsTest {

    private static final String ORIGIN = "http://app.example.com";

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
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

        GatewayHandler.writeFullResponse(nettyCtx, request("GET", "/api/orders"), backend);

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
                new GatewayException.UnauthorizedException("missing token"));

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
                "req-1", new IllegalStateException("boom"));

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

        GatewayHandler.writeFullResponse(nettyCtx, request("GET", "/api/orders"), backend);

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("http://only-this.example.com", allowOrigin(resp),
                "后端显式声明的跨域策略是那个服务的选择，网关不该改成 *");
    }
}
