package com.zgw.core.http;

import com.zgw.core.server.GatewayContext;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP Client for proxying requests to backend services
 */
public class HttpClient {

    private static final Logger logger = LogManager.getLogger(HttpClient.class);
    private static final AttributeKey<GatewayContext> CONTEXT_KEY = AttributeKey.valueOf("gatewayContext");

    private final Bootstrap bootstrap;
    private final NioEventLoopGroup workerGroup;
    private final int connectTimeout;
    private final int readTimeout;

    public HttpClient() {
        this(3000, 30000);
    }

    public HttpClient(int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;

        this.workerGroup = new NioEventLoopGroup();
        this.bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpContentDecompressor())
                                .addLast(new HttpObjectAggregator(10 * 1024 * 1024))
                                .addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS))
                                .addLast(new BackendResponseHandler());
                    }
                });
    }

    /**
     * Execute request to backend
     */
    public CompletableFuture<FullHttpResponse> execute(GatewayContext context, String backendUrl) {
        CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();

        try {
            URI uri = new URI(backendUrl);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();

            // Build request
            FullHttpRequest request = buildRequest(context, uri);

            // Connect and send
            ChannelFuture connectFuture = bootstrap.connect(host, port);

            connectFuture.addListener((ChannelFutureListener) cf -> {
                if (cf.isSuccess()) {
                    Channel channel = cf.channel();
                    channel.attr(CONTEXT_KEY).set(context);

                    // Store future in context for response handler
                    context.setAttribute("responseFuture", future);

                    channel.writeAndFlush(request).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            future.completeExceptionally(writeFuture.cause());
                            channel.close();
                        }
                    });
                } else {
                    future.completeExceptionally(cf.cause());
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Build HTTP request for backend
     */
    private FullHttpRequest buildRequest(GatewayContext context, URI uri) {
        FullHttpRequest original = context.getRequest();

        // Build target URL path
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // Append query string if present
        if (uri.getQuery() != null) {
            path += "?" + uri.getQuery();
        }

        // Create new request
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                original.protocolVersion(),
                original.method(),
                path,
                original.content().retainedDuplicate()
        );

        // Copy headers
        request.headers().set(original.headers());

        // Update host header
        request.headers().set("Host", uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));

        // Add X-Forwarded headers
        request.headers().set("X-Forwarded-For", context.getClientIp());
        request.headers().set("X-Forwarded-Proto", "http"); // TODO: detect HTTPS
        request.headers().set("X-Request-Id", context.getRequestId());

        return request;
    }

    /**
     * Shutdown the HTTP client
     */
    public void shutdown() {
        workerGroup.shutdownGracefully();
    }

    /**
     * Handler for backend responses
     */
    private static class BackendResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            GatewayContext context = ctx.channel().attr(CONTEXT_KEY).get();
            if (context == null) {
                return;
            }

            @SuppressWarnings("unchecked")
            java.util.concurrent.CompletableFuture<FullHttpResponse> future =
                    (java.util.concurrent.CompletableFuture<FullHttpResponse>) context.getAttribute("responseFuture");

            if (future != null) {
                future.complete(response.retainedDuplicate());
            }

            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            GatewayContext context = ctx.channel().attr(CONTEXT_KEY).get();
            if (context != null) {
                @SuppressWarnings("unchecked")
                java.util.concurrent.CompletableFuture<FullHttpResponse> future =
                        context.getAttribute("responseFuture");

                if (future != null) {
                    future.completeExceptionally(cause);
                }
            }

            ctx.close();
        }
    }
}