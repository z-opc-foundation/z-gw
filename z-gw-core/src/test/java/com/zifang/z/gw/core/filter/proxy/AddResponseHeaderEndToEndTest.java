package com.zifang.z.gw.core.filter.proxy;

import com.zifang.z.gw.api.FilterDefinition;
import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.DefaultGatewayFilterChain;
import com.zifang.z.gw.core.filter.factory.AddResponseHeaderFilterFactory;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.server.GatewayHandler;
import com.zifang.z.gw.core.service.LbUriResolver;
import io.netty.buffer.Unpooled;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code AddResponseHeader} 过滤器此前是一条断链：它把头写进 {@code ctx} 的
 * {@code resp.headers} attribute，而<b>全仓没有任何一处读它</b>。
 *
 * <p>过滤器自己的 Javadoc 写着「由 {@code GatewayHandler.writeFullResponse} 时合并」，
 * 而 {@code writeFullResponse(nettyCtx, request, backendResp)} 三个参数里压根没有 ctx ——
 * 它拿不到 {@code resp.headers}，也就无从合并。工厂解析是对的（它按 {@code _genkey_0}
 * 拆出了 "X-Gateway" 和 "z-gw-demo"），过滤器也真的在跑，只是结果被丢在半路。</p>
 *
 * <p>后果：{@code YamlRouteLoader.defaultRoutes()} 的演示路由明写
 * {@code AddResponseHeader=X-Gateway, z-gw-demo}，实际响应里不会有这个头。
 * 对称方向的 {@code AddRequestHeader} 是通的（{@code BackendHttpClient} 用
 * {@code target.set(...)} 合并 {@code req.headers}），只有响应方向断了。</p>
 */
class AddResponseHeaderEndToEndTest {

    private static EmbeddedChannel newChannelWithHandler() {
        return new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
    }

    private static FullHttpResponse backendResponse() {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
    }

    private static GatewayContext ctx(EmbeddedChannel channel) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setPath("/demo/ping");
        ctx.setRequestId("arh-1");
        ctx.setClientIp("1.1.1.1");
        ctx.setMatchedRoute(RouteDefinition.builder()
                .id("demo-echo").uri("http://127.0.0.1:1/demo").order(0).build());
        ctx.setAttribute("netty.ctx", channel.pipeline().firstContext());
        ctx.setAttribute("req.original", new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/demo/ping"));
        return ctx;
    }

    /** 后端立刻回 200。 */
    private static BackendHttpClient okClient() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(backendResponse()));
        return client;
    }

    private static NettyProxyFilter proxy(BackendHttpClient client) {
        return new NettyProxyFilter(client, LbUriResolver.defaults(), new ServerConfig());
    }

    private static FullHttpResponse runAndRead(EmbeddedChannel channel) {
        return channel.readOutbound();
    }

    @Test
    @DisplayName("AddResponseHeader 写的头必须真的出现在写给客户端的响应上")
    void addResponseHeaderReachesTheWire() {
        GatewayFilter addHeader = new AddResponseHeaderFilterFactory()
                .apply(FilterDefinition.of("AddResponseHeader", "X-Gateway, z-gw-demo").getArgs());

        EmbeddedChannel channel = newChannelWithHandler();
        DefaultGatewayFilterChain.execute(Arrays.asList(addHeader, proxy(okClient())), ctx(channel));

        FullHttpResponse resp = runAndRead(channel);
        assertEquals("z-gw-demo", resp.headers().get("X-Gateway"),
                "过滤器声明的头必须落到出站响应；未修实现里 resp.headers 无人读取（实际头: "
                        + resp.headers() + "）");
    }

    @Test
    @DisplayName("AddResponseHeader 简写能拆出头名与头值（对照组：工厂侧本来就是对的）")
    void factoryShorthandParsingIsIntact() {
        GatewayFilter addHeader = new AddResponseHeaderFilterFactory()
                .apply(FilterDefinition.of("AddResponseHeader", "X-Gateway, z-gw-demo").getArgs());
        GatewayContext probe = new GatewayContext();

        addHeader.filter(probe, c -> { });

        Map<String, String> respHeaders = probe.getAttribute("resp.headers", Map.class);
        assertEquals("z-gw-demo", respHeaders.get("X-Gateway"),
                "工厂按 _genkey_0 约定拆出了头名/头值，这一步一直是对的");
    }

    @Test
    @DisplayName("没配 AddResponseHeader 时不应凭空多出头")
    void noFilterNoExtraHeader() {
        EmbeddedChannel channel = newChannelWithHandler();
        DefaultGatewayFilterChain.execute(
                Collections.singletonList(proxy(okClient())), ctx(channel));

        FullHttpResponse resp = runAndRead(channel);
        assertNull(resp.headers().get("X-Gateway"), "没配过滤器就不该有这个头");
    }

    @Test
    @DisplayName("过滤器显式设置的头覆盖后端同名头（与请求方向 target.set(...) 的既定优先级一致）")
    void explicitFilterWinsOverBackendHeader() {
        FullHttpResponse backend = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        backend.headers().set("X-Gateway", "backend-value");
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(backend));

        GatewayFilter addHeader = new AddResponseHeaderFilterFactory()
                .apply(FilterDefinition.of("AddResponseHeader", "X-Gateway, gateway-value").getArgs());

        EmbeddedChannel channel = newChannelWithHandler();
        DefaultGatewayFilterChain.execute(Arrays.asList(addHeader, proxy(client)), ctx(channel));

        FullHttpResponse resp = runAndRead(channel);
        assertEquals("gateway-value", resp.headers().get("X-Gateway"),
                "AddResponseHeader 是路由级显式配置，应当覆盖后端；请求方向的 "
                        + "BackendHttpClient.copyEndToEndHeaders 用的就是 target.set(...)");
    }

    @Test
    @DisplayName("过滤器可以显式覆盖网关给的 CORS 默认值（ACAO 的 contains 判断只针对后端，不锁网关自己的配置）")
    void explicitFilterCanOverrideTheCorsDefault() {
        GatewayFilter addHeader = new AddResponseHeaderFilterFactory()
                .apply(FilterDefinition.of("AddResponseHeader",
                        "Access-Control-Allow-Origin, https://app.example.com").getArgs());

        EmbeddedChannel channel = newChannelWithHandler();
        DefaultGatewayFilterChain.execute(Arrays.asList(addHeader, proxy(okClient())), ctx(channel));

        FullHttpResponse resp = runAndRead(channel);
        assertEquals("https://app.example.com",
                resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "实际头: " + resp.headers());
    }

    @Test
    @DisplayName("后端自带的 ACAO 仍不被网关的 * 覆盖（对照组：既有行为没被改坏）")
    void backendCorsHeaderStillWins() {
        FullHttpResponse backend = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        backend.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "https://backend.example.com");
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(backend));

        EmbeddedChannel channel = newChannelWithHandler();
        DefaultGatewayFilterChain.execute(
                Collections.singletonList(proxy(client)), ctx(channel));

        FullHttpResponse resp = runAndRead(channel);
        assertEquals("https://backend.example.com",
                resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("三参 writeFullResponse 仍可用（对照组：既有测试与外部调用方不受影响）")
    void threeArgOverloadStillWorks() {
        EmbeddedChannel channel = newChannelWithHandler();
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/demo/ping");
        GatewayHandler.writeFullResponse(channel.pipeline().firstContext(), req, backendResponse());

        FullHttpResponse resp = runAndRead(channel);
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
                "三参版没有 CORS 策略可依（corsEnabled 默认 false），所以不该补 ACAO；"
                        + "此前它无条件写 *，与 corsEnabled 声明的默认值相反");
    }

    @Test
    @DisplayName("带策略的 writeFullResponse：跨域开启时补 ACAO（对照组：显式传策略才补）")
    void fullResponseWithPolicyAddsAllowOrigin() {
        EmbeddedChannel channel = newChannelWithHandler();
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/demo/ping");
        req.headers().set(HttpHeaderNames.ORIGIN, "http://app.example.com");
        com.zifang.z.gw.core.config.ServerConfig cfg = new com.zifang.z.gw.core.config.ServerConfig();
        cfg.setCorsEnabled(true);
        GatewayHandler.writeFullResponse(channel.pipeline().firstContext(), req, backendResponse(), null,
                com.zifang.z.gw.core.config.CorsPolicy.from(cfg));

        FullHttpResponse resp = runAndRead(channel);
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals("*", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
