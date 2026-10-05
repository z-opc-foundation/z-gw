package com.zifang.z.gw.core.filter.proxy;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.api.ServiceInstance;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.lb.LeastConnectionsLoadBalancer;
import com.zifang.z.gw.core.service.LbUriResolver;
import com.zifang.z.gw.core.service.discovery.StaticServiceDiscovery;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code LeastConnectionsLoadBalancer} 的输入必须真的有人维护。
 *
 * <p>{@code ServiceInstance.activeConnections} 全仓只有 getter/setter，零生产调用方——
 * {@code NettyProxyFilter} 解析出实例、转发、等响应、写回，一路没有碰过这个计数。
 * 于是每个实例恒为 0，而 {@code select} 里的 {@code conns < min} 在第一个实例就命中
 * （0 &lt; MAX_VALUE），后面全部被 {@code 0 < 0} 挡掉 ⇒ <b>永远返回 instances.get(0)</b>。
 * 类注释承诺的"优先选当前活跃连接最少的实例"与 README 第 43 行的算法清单都成了摆设，
 * 配了 leastConnections 的服务等于把全部流量压到第一个实例上。</p>
 *
 * <p>纯 LB 单测抓不到这个洞：测试里自己调 {@code incrementActiveConnections()} 的话
 * 算法本来就对。判据必须走真实请求生命周期——<b>转发的那一刻被选中的实例要正在计自己，
 * 请求结束后计数必须归零</b>。</p>
 */
class LeastConnectionsInFlightTest {

    private static final int INSTANCES = 3;

    private final StaticServiceDiscovery discovery = new StaticServiceDiscovery();
    private final List<ServiceInstance> pool = new ArrayList<>();

    private LeastConnectionsInFlightTest() {
        for (int i = 0; i < INSTANCES; i++) {
            ServiceInstance ins = ServiceInstance.builder()
                    .serviceId("svc")
                    .instanceId("i" + i)
                    .host("10.0.0." + i)
                    .port(8080 + i)
                    .build();
            discovery.register(ins);
            pool.add(ins);
        }
    }

    /** EmbeddedChannel 不装 handler 时 firstContext() 返回 null，setAttribute 会 NPE。 */
    private static EmbeddedChannel newChannelWithHandler() {
        return new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
    }

    private static GatewayContext lbCtx(EmbeddedChannel channel) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setPath("/api/x");
        ctx.setRequestId("lc-1");
        ctx.setClientIp("1.1.1.1");
        ctx.setMatchedRoute(RouteDefinition.builder()
                .id("r1").uri("lb://svc").order(1).build());
        ctx.setAttribute("netty.ctx", channel.pipeline().firstContext());
        ctx.setAttribute("req.original", new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/x"));
        return ctx;
    }

    private NettyProxyFilter filter(BackendHttpClient client) {
        return new NettyProxyFilter(client,
                new LbUriResolver(discovery, new LeastConnectionsLoadBalancer()),
                new ServerConfig());
    }

    private int total() {
        int sum = 0;
        for (ServiceInstance ins : pool) sum += ins.getActiveConnections();
        return sum;
    }

    /** 后端立刻回 200；在转发那一刻把所有实例的计数拍一张快照。 */
    @Test
    @DisplayName("转发进行中，被选中的实例正在计自己（判据：没有这条，最少连接恒为「永远第一个」）")
    void selectedInstanceCountsItselfWhileInFlight() {
        List<String> snapshotAtDispatch = new ArrayList<>();
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any())).thenAnswer(inv -> {
            ServiceInstance picked = inv.getArgument(1);
            StringBuilder sb = new StringBuilder();
            for (ServiceInstance ins : pool) {
                sb.append(ins.getInstanceId()).append('=').append(ins.getActiveConnections()).append(' ');
            }
            snapshotAtDispatch.add("picked=" + picked.getInstanceId() + " | " + sb);
            return CompletableFuture.completedFuture(ok());
        });

        EmbeddedChannel channel = newChannelWithHandler();
        filter(client).filter(lbCtx(channel), mock(GatewayFilterChain.class));

        assertEquals(1, snapshotAtDispatch.size(), "后端应被调用一次");
        String snap = snapshotAtDispatch.get(0);
        assertTrue(snap.contains("=1 "),
                "转发这一刻，选中的实例必须正在计自己（=1），其余为 0；实测快照：" + snap);
    }

    /** 后端挂死不响应：两个请求同时在飞，第三个必须被推到当前最闲的实例。 */
    @Test
    @DisplayName("两个请求在飞时，第三个请求被发往当前最闲的实例（真·最少连接）")
    void thirdRequestGoesToTheIdleInstance() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(2);
        List<CompletableFuture<FullHttpResponse>> held = new ArrayList<>();
        AtomicReference<ServiceInstance> inFlight = new AtomicReference<>();

        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any())).thenAnswer(inv -> {
            ServiceInstance ins = inv.getArgument(1);
            inFlight.compareAndSet(null, ins);
            dispatched.countDown();
            CompletableFuture<FullHttpResponse> f = new CompletableFuture<>();
            synchronized (held) { held.add(f); }
            return f;
        });

        NettyProxyFilter f = filter(client);
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            EmbeddedChannel ch1 = newChannelWithHandler();
            EmbeddedChannel ch2 = newChannelWithHandler();
            pool2.submit(() -> f.filter(lbCtx(ch1), mock(GatewayFilterChain.class)));
            pool2.submit(() -> f.filter(lbCtx(ch2), mock(GatewayFilterChain.class)));

            assertTrue(dispatched.await(5, TimeUnit.SECONDS),
                    "两个请求都应已转发出去；实际在飞计数 = " + total());
            assertEquals(2, total(),
                    "两个在飞请求应各自给实例计上 1；未修实现这里恒为 0（计数器无人维护）");

            // 此刻两个实例各 1、一个 0。第三次选择必须落到那个 0 上。
            ServiceInstance third = new LeastConnectionsLoadBalancer().select("svc", pool, lbCtx(newChannelWithHandler()));
            assertNotEquals(inFlight.get().getInstanceId(), third.getInstanceId(),
                    "在飞的请求占着 " + inFlight.get().getInstanceId()
                            + "，最少连接必须把第三个请求发往当前连接数更少的实例");
            assertEquals(0, third.getActiveConnections(),
                    "被选中的应是当前最闲（计数最小）的实例");
        } finally {
            pool2.shutdownNow();
        }
    }

    @Test
    @DisplayName("请求结束后计数归零（不归还 ⇒ 计数只增不减，实例会被永久排除）")
    void countersReturnToZeroAfterSuccess() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(ok()));

        EmbeddedChannel channel = newChannelWithHandler();
        filter(client).filter(lbCtx(channel), mock(GatewayFilterChain.class));

        assertEquals(0, total(), "转发结束后所有实例的在飞计数必须归零");
    }

    @Test
    @DisplayName("后端失败时计数同样归零（异常路径不归还 = 一次后端抖动废掉一个实例）")
    void countersReturnToZeroAfterBackendFailure() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        CompletableFuture<FullHttpResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new java.io.IOException("backend down"));
        when(client.execute(any(), any(), anyString(), any())).thenReturn(failed);

        EmbeddedChannel channel = newChannelWithHandler();
        try {
            filter(client).filter(lbCtx(channel), mock(GatewayFilterChain.class));
            fail("后端失败应抛 BadGatewayException");
        } catch (GatewayException expected) {
            // 预期
        }
        assertEquals(0, total(), "后端失败退出时也必须把名额还回去");
    }

    @Test
    @DisplayName("后端超时时计数同样归零")
    void countersReturnToZeroAfterTimeout() {
        BackendHttpClient client = mock(BackendHttpClient.class);
        when(client.execute(any(), any(), anyString(), any()))
                .thenAnswer(inv -> new CompletableFuture<>());   // 永不响应
        ServerConfig cfg = new ServerConfig();
        cfg.setReadTimeoutMs(60);
        NettyProxyFilter f = new NettyProxyFilter(client,
                new LbUriResolver(discovery, new LeastConnectionsLoadBalancer()), cfg);

        EmbeddedChannel channel = newChannelWithHandler();
        try {
            f.filter(lbCtx(channel), mock(GatewayFilterChain.class));
            fail("后端不响应应抛 GatewayTimeoutException");
        } catch (GatewayException expected) {
            // 预期
        }
        assertEquals(0, total(), "超时退出时也必须把名额还回去");
    }

    private static FullHttpResponse ok() {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
    }
}
