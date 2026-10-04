package com.zifang.z.gw.core.filter.proxy;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.service.LbUriResolver;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NettyProxyFilter} 的后端等待上限必须来自配置。
 *
 * <p>此前这里是硬编码 {@code future.get(30, SECONDS)}，而
 * {@code ServerConfig.readTimeoutMs} 的默认值恰好也是 30000，看着"对得上"——
 * 实则是<b>两个互不相干的上限</b>：运维把 readTimeoutMs 调大时出站等更久，
 * 这里仍是 30 秒就返回超时，配置形同虚设。</p>
 */
class NettyProxyFilterTimeoutTest {

    /** EmbeddedChannel 不装 handler 时 firstContext() 返回 null，setAttribute 会 NPE。 */
    private static EmbeddedChannel newChannelWithHandler() {
        return new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
    }

    private static GatewayContext ctxWithNetty(EmbeddedChannel channel) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setPath("/api/x");
        ctx.setRequestId("t-1");
        ctx.setMatchedRoute(RouteDefinition.builder()
                .id("r1").uri("http://127.0.0.1:1/api").order(1).build());
        ctx.setAttribute("netty.ctx", channel.pipeline().firstContext());
        ctx.setAttribute("req.original", new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/x"));
        return ctx;
    }

    /** 后端永不响应：future 永不完成，于是只能靠等待上限兜底。 */
    private static BackendHttpClient neverAnsweringClient() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenAnswer(inv -> new CompletableFuture<>());
        return client;
    }

    @Test
    @DisplayName("等待上限跟随 ServerConfig.readTimeoutMs，而不是硬编码 30 秒")
    void timeoutComesFromServerConfig() {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(80);
        NettyProxyFilter filter =
                new NettyProxyFilter(neverAnsweringClient(), LbUriResolver.defaults(), cfg);

        EmbeddedChannel channel = newChannelWithHandler();
        GatewayContext ctx = ctxWithNetty(channel);

        long t0 = System.nanoTime();
        assertThrows(GatewayException.class, () -> filter.filter(ctx, mock(GatewayFilterChain.class)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(elapsedMs < 3_000L,
                "应在配置的 80ms 附近超时；若实现仍硬编码 30 秒，这里会是 ~30000ms（实际 "
                        + elapsedMs + "ms）");
    }

    @Test
    @DisplayName("调大 readTimeoutMs 时等待上限确实跟着变大（对照组：证明上条不是靠默认 30 秒碰巧）")
    void largerTimeoutIsActuallyHonoured() {
        ServerConfig shortCfg = new ServerConfig();
        shortCfg.setReadTimeoutMs(60);
        ServerConfig longCfg = new ServerConfig();
        longCfg.setReadTimeoutMs(600);

        long shortMs = timeUntilTimeout(new NettyProxyFilter(
                neverAnsweringClient(), LbUriResolver.defaults(), shortCfg));
        long longMs = timeUntilTimeout(new NettyProxyFilter(
                neverAnsweringClient(), LbUriResolver.defaults(), longCfg));

        assertTrue(longMs > shortMs * 3,
                "配置 600ms 应明显长于 60ms（实际 " + shortMs + "ms vs " + longMs + "ms）");
    }

    @Test
    @DisplayName("后端不响应时抛的是 GatewayTimeoutException（对照组：异常类型没被改坏）")
    void timeoutRaisesGatewayTimeout() {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(60);
        NettyProxyFilter filter =
                new NettyProxyFilter(neverAnsweringClient(), LbUriResolver.defaults(), cfg);

        EmbeddedChannel channel = newChannelWithHandler();
        GatewayException e = assertThrows(GatewayException.class,
                () -> filter.filter(ctxWithNetty(channel), mock(GatewayFilterChain.class)));
        assertTrue(e instanceof GatewayException.GatewayTimeoutException,
                "实际: " + e.getClass().getName());
    }

    private static long timeUntilTimeout(NettyProxyFilter filter) {
        EmbeddedChannel channel = newChannelWithHandler();
        long t0 = System.nanoTime();
        try {
            filter.filter(ctxWithNetty(channel), mock(GatewayFilterChain.class));
        } catch (GatewayException expected) {
            // 超时即预期结果
        }
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
