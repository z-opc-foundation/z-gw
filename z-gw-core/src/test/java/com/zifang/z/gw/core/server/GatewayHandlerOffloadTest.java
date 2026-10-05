package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.global.ErrorHandlingGlobalFilter;
import com.zifang.z.gw.core.filter.proxy.NettyProxyFilter;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.gw.core.service.LbUriResolver;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 过滤器链必须在业务线程池上跑，<b>不能占着 Netty EventLoop</b>。
 *
 * <p>{@code GatewayHandler.channelRead0} 跑在 workerGroup 的 EventLoop 上，而链尾
 * {@code NettyProxyFilter} 会 {@code future.get(readTimeoutMs)} 阻塞等后端响应。此前
 * {@code ServerConfig} 上"业务线程池(阻塞操作放这里,避免占用 Netty EventLoop)"注释
 * 点名的三个参数<b>只有声明和 getter/setter，全仓零读取点</b>——那次阻塞就一直在
 * EventLoop 上。</p>
 *
 * <p>代价是双重的：① EventLoop 被占住时，同一 EventLoop 上<b>所有</b>通道的读、写、
 * 超时检测全部停摆，几个慢后端就能把整个网关拖死；② 每个 EventLoop 线程同时只能处理
 * 一个请求，并发被压到"EventLoop 线程数"个（默认 CPU×2）。</p>
 */
class GatewayHandlerOffloadTest {

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    /** 后端永不响应：只有等 readTimeoutMs 才会结束。 */
    private static BackendHttpClient neverAnsweringClient() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenAnswer(inv -> new CompletableFuture<>());
        return client;
    }

    private static EmbeddedChannel channel(ServerConfig cfg) {
        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new ErrorHandlingGlobalFilter());
        assembler.addGlobalFilter(new NettyProxyFilter(
                neverAnsweringClient(), LbUriResolver.defaults(), cfg));

        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/api").order(1).build()));

        return new EmbeddedChannel(new GatewayHandler(cfg, matcher, assembler, null));
    }

    private static FullHttpResponse awaitOutbound(EmbeddedChannel ch, long budgetMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            FullHttpResponse r = ch.readOutbound();
            if (r != null) {
                return r;
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    @DisplayName("后端挂住时 channelRead0 立即返回，不阻塞 EventLoop")
    void eventLoopIsNotBlocked() throws Exception {
        ServerConfig cfg = new ServerConfig();
        // 等待上限给足 8 秒：若仍在 EventLoop 上阻塞，这次 writeInbound 就会卡满 8 秒
        cfg.setReadTimeoutMs(8000);
        EmbeddedChannel ch = channel(cfg);

        long t0 = System.nanoTime();
        ch.writeInbound(get("/api/x"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(elapsedMs < 2000L,
                "channelRead0 阻塞了 " + elapsedMs + "ms —— 过滤器链还在 Netty EventLoop 上跑；"
                        + "后端等待上限是 8000ms，正常应当是投递后立刻返回");
    }

    @Test
    @DisplayName("端到端：后端超时最终真的回 504（等待上限仍跟随配置）")
    void backendTimeoutYields504() throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(150);
        EmbeddedChannel ch = channel(cfg);

        long t0 = System.nanoTime();
        ch.writeInbound(get("/api/x"));
        FullHttpResponse resp = awaitOutbound(ch, 5000);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertNotNull(resp, "后端超时后没有任何响应写出");
        assertEquals(HttpResponseStatus.GATEWAY_TIMEOUT, resp.status(),
                "后端超时应回 504，实际 " + resp.status());
        assertTrue(elapsedMs < 3000L,
                "应在配置的 150ms 附近回 504，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("两条不同 readTimeoutMs 的超时时长确实不同（对照组：不是靠默认值碰巧）")
    void configuredTimeoutIsActuallyHonoured() throws Exception {
        long shortMs = timeUntil504(80);
        long longMs = timeUntil504(800);

        assertTrue(longMs > shortMs * 3,
                "配置 800ms 应明显长于 80ms（实际 " + shortMs + "ms vs " + longMs + "ms）");
    }

    private static long timeUntil504(int readTimeoutMs) throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(readTimeoutMs);
        EmbeddedChannel ch = channel(cfg);

        long t0 = System.nanoTime();
        ch.writeInbound(get("/api/x"));
        FullHttpResponse resp = awaitOutbound(ch, 5000);
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        assertNotNull(resp, readTimeoutMs + "ms 那次没等到 504");
        assertEquals(HttpResponseStatus.GATEWAY_TIMEOUT, resp.status());
        return elapsed;
    }

    @Test
    @DisplayName("业务线程池大小真的按 businessThreadCore 建（此前是死参数）")
    void businessThreadParamsAreNotDead() throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(100);
        cfg.setBusinessThreadCore(3);
        cfg.setBusinessThreadMax(3);

        GatewayHandler handler = new GatewayHandler(cfg, new RouteMatcher(), null, null);
        assertEquals(3, handler.businessPoolSize(),
                "businessThreadCore=3 却建了 " + handler.businessPoolSize()
                        + " 个线程 —— 这三个参数此前零读取点");
        handler.shutdown();
    }

    @Test
    @DisplayName("业务线程池满时回错误响应，而不是静默丢请求（对照组：过载不挂死）")
    void saturatedPoolStillResponds() throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(2000);
        cfg.setBusinessThreadCore(1);
        cfg.setBusinessThreadMax(1);
        cfg.setBusinessQueue(1);

        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new ErrorHandlingGlobalFilter());
        assembler.addGlobalFilter(new NettyProxyFilter(
                neverAnsweringClient(), LbUriResolver.defaults(), cfg));
        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/api").order(1).build()));
        EmbeddedChannel ch = new EmbeddedChannel(new GatewayHandler(cfg, matcher, assembler, null));

        // 队列(1) + 正在跑的(1) 占满后，第 4 个请求必然被拒
        for (int i = 0; i < 4; i++) {
            ch.writeInbound(get("/api/x"));
        }

        FullHttpResponse resp = awaitOutbound(ch, 5000);
        assertNotNull(resp, "过载时也没有任何响应写出");
        assertEquals(HttpResponseStatus.BAD_GATEWAY, resp.status(),
                "过载应回错误响应而非静默丢弃，实际 " + resp.status());
        assertTrue(resp.content().toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("overloaded"),
                "过载消息应说明是过载: " + resp.content().toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
