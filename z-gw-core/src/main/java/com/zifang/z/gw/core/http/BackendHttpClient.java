package com.zifang.z.gw.core.http;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.ServiceInstance;
import com.zifang.z.gw.core.config.ServerConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 出站 HTTP 客户端 — 基于 Netty 实现,异步非阻塞,带连接池友好的 channel 复用。
 *
 * <p>设计参考 SCG 的 {@code NettyRoutingFilter}:
 * <ul>
 *   <li>每个请求一个 {@link CompletableFuture},handler 收到响应时 complete</li>
 *   <li><b>只支持明文 HTTP</b>。TLS 尚未实现：pipeline 里没有 SslHandler。
 *       因此 {@code LbUriResolver} 会明确拒绝 {@code https://} 路由，而不是降级成明文
 *       —— 降级等于把 Authorization / Cookie 明文发出去。</li>
 *   <li>支持 HTTP 1.1(简化版,HTTP/2 后续通过 Netty Http2Channel 升级)</li>
 * </ul>
 */
public class BackendHttpClient {

    private static final Logger log = LoggerFactory.getLogger(BackendHttpClient.class);
    private static final AttributeKey<CompletableFuture<FullHttpResponse>> FUTURE_KEY =
            AttributeKey.valueOf("zgw.backend.future");

    private final ServerConfig config;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;

    public BackendHttpClient(ServerConfig config) {
        this.config = config;
        this.workerGroup = new NioEventLoopGroup(config.getMaxPoolSize() / 4 + 1);
        this.bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpContentDecompressor())
                                .addLast(new HttpObjectAggregator(config.getMaxContentLength()))
                                .addLast(new ReadTimeoutHandler(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS))
                                .addLast(new WriteTimeoutHandler(config.getWriteTimeoutMs(), TimeUnit.MILLISECONDS))
                                .addLast(new BackendResponseHandler());
                    }
                });
    }

    /**
     * 发送请求到后端服务实例。
     *
     * @return 后端响应的 future
     */
    public CompletableFuture<FullHttpResponse> execute(GatewayContext ctx, ServiceInstance instance, String path, String query) {
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
        try {
            String uri = "http://" + instance.address() + (path == null ? "/" : path) + (query == null ? "" : "?" + query);
            FullHttpRequest req = buildRequest(ctx, uri);
            ChannelFuture cf = bootstrap.connect(instance.getHost(), instance.getPort());
            cf.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    Channel ch = f.channel();
                    ch.attr(FUTURE_KEY).set(future);
                    ch.writeAndFlush(req).addListener(wf -> {
                        if (!wf.isSuccess()) {
                            future.completeExceptionally(wf.cause());
                            ch.close();
                        }
                    });
                } else {
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 包可见（非 private）便于测试直接断言出站请求的头集合；生产可见性不变。 */
    static FullHttpRequest buildRequest(GatewayContext ctx, String targetUri) throws Exception {
        URI uri = new URI(targetUri);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.valueOf(ctx.getMethod()),
                path,
                ctx.getAttribute("req.body.bytes", byte[].class) != null
                        ? Unpooled.wrappedBuffer(ctx.getAttribute("req.body.bytes", byte[].class))
                        : Unpooled.EMPTY_BUFFER
        );

        // 复制原始请求头（只复制端到端头）
        @SuppressWarnings("unchecked")
        Map<String, String> headers = ctx.getAttribute("req.headers", Map.class);
        if (headers != null) {
            copyEndToEndHeaders(req.headers(), headers);
        }

        // 更新 Host
        req.headers().set(HttpHeaderNames.HOST, uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));

        // 注入追踪信息
        if (ctx.getRequestId() != null) {
            req.headers().set("X-Request-Id", ctx.getRequestId());
        }
        if (ctx.getClientIp() != null) {
            req.headers().set("X-Forwarded-For", ctx.getClientIp());
        }

        // Content-Length
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, req.content().readableBytes());
        return req;
    }

    /**
     * 只把<b>端到端</b>头复制到出站请求（RFC 7230 §6.1）。
     *
     * <p>hop-by-hop 头属于<b>本次连接</b>，不能被代理转发。最要紧的是
     * {@code Transfer-Encoding}：{@link #buildRequest} 自己会按实际 body 设置
     * {@code Content-Length}（body 已被 {@code HttpObjectAggregator} 完整聚合），
     * 若客户端的 {@code TE: chunked} 一起带过去，出站请求上两个 body 边界语义
     * 同时存在且互相矛盾——前置代理与后端理解不一致时即构成 HTTP 请求走私。</p>
     *
     * <p>同仓的响应方向（{@code GatewayHandler.writeFullResponse}）本就写了
     * {@code // 去掉 hop-by-hop headers}，此前只有请求方向漏了。</p>
     */
    private static void copyEndToEndHeaders(HttpHeaders target, Map<String, String> src) {
        Set<String> drop = new HashSet<>(HOP_BY_HOP_HEADERS);
        // Connection 头点名的那些头也要剥掉（RFC 7230 §6.1 第 2 步）
        for (Map.Entry<String, String> e : src.entrySet()) {
            if ("connection".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                for (String token : e.getValue().split(",")) {
                    String t = token.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) {
                        drop.add(t);
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (drop.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            target.set(e.getKey(), e.getValue());
        }
    }

    /** RFC 7230 §6.1 列出的固定 hop-by-hop 头（均按小写比较）。 */
    private static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"));

    public void shutdown() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * 后端响应处理 — 解析 FullHttpResponse 后 complete future。
     */
    private static class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext chc, FullHttpResponse response) {
            CompletableFuture<FullHttpResponse> f = chc.channel().attr(FUTURE_KEY).get();
            if (f != null) {
                f.complete(response.retainedDuplicate());
            }
            chc.channel().close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext chc, Throwable cause) {
            log.warn("Backend response error: {}", cause.getMessage());
            CompletableFuture<FullHttpResponse> f = chc.channel().attr(FUTURE_KEY).get();
            if (f != null && !f.isDone()) {
                f.completeExceptionally(cause);
            }
            chc.channel().close();
        }
    }

    /** 工具:将字符串 body 转为 byte[] 塞入 ctx(供 buildRequest 使用) */
    public static void setBody(GatewayContext ctx, String body) {
        if (body != null) {
            ctx.setAttribute("req.body.bytes", body.getBytes(CharsetUtil.UTF_8));
        }
    }
}
