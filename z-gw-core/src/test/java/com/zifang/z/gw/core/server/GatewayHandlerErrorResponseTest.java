package com.zifang.z.gw.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.global.ErrorHandlingGlobalFilter;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过滤器链抛出的异常必须变成<b>真的 HTTP 响应</b>。
 *
 * <p>{@link ErrorHandlingGlobalFilter} 的 Javadoc 写着"捕获过滤器链中抛出的异常,转为
 * 对应 HTTP 错误响应"，实现却只记日志 + 设 {@code resp.status} 属性。全仓读
 * {@code resp.status} 的只有 {@code MetricsGlobalFilter}（记指标）和
 * {@code LoggingGlobalFilter}（写访问日志）——没有任何代码据此往连接写东西。</p>
 *
 * <p>后果：401/403/429/502/503/504 全部<b>一个字节都发不出去</b>，客户端挂到入站
 * {@code ReadTimeoutHandler}(60s) 被动断开。修复前本文件的 {@link #rateLimitedYields429}
 * 实测出站为 {@code null}、通道仍 active。</p>
 */
class GatewayHandlerErrorResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    /** 装一条能匹配 /x 的路由，链上挂 ErrorHandling + 一个按需抛异常的过滤器。 */
    private static EmbeddedChannel channelWithThrowing(GatewayFilter throwing, int order) {
        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new ErrorHandlingGlobalFilter());
        GatewayFilter f = order == 0 ? throwing : wrap(throwing, order);
        assembler.addGlobalFilter(f);

        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/x").order(1).build()));

        return new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), matcher, assembler, null));
    }

    private static GatewayFilter wrap(GatewayFilter delegate, int order) {
        return new GatewayFilter() {
            @Override public String name() { return "Wrapping"; }
            @Override public int order() { return order; }
            @Override public void filter(GatewayContext ctx, GatewayFilterChain chain) {
                delegate.filter(ctx, chain);
            }
        };
    }

    private static GatewayFilter throwing(GatewayException e) {
        return new GatewayFilter() {
            @Override public String name() { return "Throwing"; }
            @Override public int order() { return 950; }
            @Override public void filter(GatewayContext ctx, GatewayFilterChain chain) {
                throw e;
            }
        };
    }

    /** 链在业务线程池上跑，出站要轮询等一小会儿。 */
    private static FullHttpResponse awaitOutbound(EmbeddedChannel ch) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            FullHttpResponse r = ch.readOutbound();
            if (r != null) {
                return r;
            }
            Thread.sleep(10);
        }
        return null;
    }

    // ==================================================================

    @Test
    @DisplayName("过滤器抛 429 时客户端必须真的收到 429（修复前出站为 null）")
    void rateLimitedYields429() throws Exception {
        EmbeddedChannel ch = channelWithThrowing(
                throwing(new GatewayException.RateLimitedException("too many", 1)), 0);

        ch.writeInbound(get("/x"));

        FullHttpResponse resp = awaitOutbound(ch);
        assertNotNull(resp, "过滤器抛 GatewayException 后没有任何响应写出：客户端会一直挂到 60s 读超时");
        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS, resp.status());

        JsonNode body = MAPPER.readTree(resp.content().toString(StandardCharsets.UTF_8));
        assertEquals("RATE_LIMITED", body.get("error").asText());
        assertEquals("too many", body.get("message").asText());
    }

    @Test
    @DisplayName("401/403 同样要真的写出去（对照组：不是只对 429 生效）")
    void authFailuresAlsoProduceResponses() throws Exception {
        for (GatewayException ge : new GatewayException[]{
                new GatewayException.UnauthorizedException("no token"),
                new GatewayException.ForbiddenException("denied")}) {

            EmbeddedChannel ch = channelWithThrowing(throwing(ge), 0);
            ch.writeInbound(get("/x"));

            FullHttpResponse resp = awaitOutbound(ch);
            assertNotNull(resp, ge.getClass().getSimpleName() + " 没有写出响应");
            assertEquals(ge.getHttpStatus(), resp.status().code(),
                    ge.getClass().getSimpleName() + " 的状态码不对");
        }
    }

    @Test
    @DisplayName("非 GatewayException 兜底成 500，而不是无响应")
    void runtimeExceptionYields500() throws Exception {
        EmbeddedChannel ch = channelWithThrowing(new GatewayFilter() {
            @Override public String name() { return "Boom"; }
            @Override public int order() { return 950; }
            @Override public void filter(GatewayContext ctx, GatewayFilterChain chain) {
                throw new IllegalStateException("boom");
            }
        }, 0);

        ch.writeInbound(get("/x"));

        FullHttpResponse resp = awaitOutbound(ch);
        assertNotNull(resp, "非 GatewayException 也没有写出响应");
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, resp.status());
    }

    @Test
    @DisplayName("错误消息里的控制字符必须转义（否则响应体不是合法 JSON）")
    void controlCharsInErrorMessageAreEscaped() throws Exception {
        EmbeddedChannel ch = channelWithThrowing(
                throwing(new GatewayException.BadGatewayException("bad\treason")), 0);

        ch.writeInbound(get("/x"));
        FullHttpResponse resp = awaitOutbound(ch);
        assertNotNull(resp);

        String body = resp.content().toString(StandardCharsets.UTF_8);
        assertTrue(!body.contains("\t"), "响应体里出现了原始 tab: " + body);
        JsonNode node = MAPPER.readTree(body);
        assertEquals("bad\treason", node.get("message").asText());
    }

    @Test
    @DisplayName("同一请求只写一个响应：先写成功再抛异常不得发第二个")
    void noDoubleWrite() throws Exception {
        FilterAssembler assembler = new FilterAssembler();
        // order 800 的过滤器先写出一个 204，再让 order 950 的过滤器抛异常
        assembler.addGlobalFilter(new GatewayFilter() {
            @Override public String name() { return "EarlyWriter"; }
            @Override public int order() { return 800; }
            @Override public void filter(GatewayContext ctx, GatewayFilterChain chain) {
                ChannelHandlerContextHolder.write(ctx);
                chain.filter(ctx);
            }
        });
        assembler.addGlobalFilter(new ErrorHandlingGlobalFilter());
        assembler.addGlobalFilter(throwing(
                new GatewayException.BadGatewayException("late failure")));

        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/x").order(1).build()));
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), matcher, assembler, null));

        ch.writeInbound(get("/x"));
        Thread.sleep(300);   // 给业务线程池时间跑完

        int written = 0;
        FullHttpResponse first;
        while ((first = ch.readOutbound()) != null) {
            written++;
            first.release();
        }
        assertEquals(1, written, "同一条连接上写了多个 HTTP 响应，客户端会先炸");
    }

    /** 借 GatewayHandler 的静态写入方法拿到 netty ctx 并写一个响应。 */
    static final class ChannelHandlerContextHolder {
        static void write(GatewayContext ctx) {
            io.netty.channel.ChannelHandlerContext nettyCtx =
                    ctx.getAttribute("netty.ctx", io.netty.channel.ChannelHandlerContext.class);
            FullHttpRequest req = ctx.getAttribute("req.original", FullHttpRequest.class);
            if (nettyCtx != null && req != null) {
                GatewayHandler.claimResponse(ctx);
                GatewayHandler.writeJson(nettyCtx, req, 204, "{}");
            }
        }
    }

    @Test
    @DisplayName("未命中路由的 404 仍走 EventLoop 直接写出（对照组：同步路径没被改坏）")
    void plainNotFoundStillWorks() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
        ch.writeInbound(get("/nope"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
    }

    @Test
    @DisplayName("健康检查仍同步直达（对照组）")
    void healthStillWorks() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
        ch.writeInbound(get("/health"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("{\"status\":\"UP\"}", resp.content().toString(StandardCharsets.UTF_8));
    }
}
