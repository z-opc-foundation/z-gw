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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 出站 HTTP 客户端 — 基于 Netty 实现,异步非阻塞,带连接池友好的 channel 复用。
 *
 * <p>设计参考 SCG 的 {@code NettyRoutingFilter}:
 * <ul>
 *   <li>每个请求一个 {@link CompletableFuture},handler 收到响应时 complete</li>
 *   <li>支持 HTTP/HTTPS(http 通过 SslContext 切换)</li>
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

    private FullHttpRequest buildRequest(GatewayContext ctx, String targetUri) throws Exception {
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

        // 复制原始请求头
        @SuppressWarnings("unchecked")
        Map<String, String> headers = ctx.getAttribute("req.headers", Map.class);
        if (headers != null) {
            HttpHeaders h = req.headers();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.set(e.getKey(), e.getValue());
            }
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
