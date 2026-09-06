package com.zgw.core.server;

import com.zgw.core.config.GatewayConfig;
import com.zgw.core.http.HttpClient;
import com.zgw.core.router.RouteResult;
import com.zgw.core.router.Router;
import com.zgw.core.router.SimpleRouter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.zifang.util.core.lang.RandomUtil;

/**
 * Main gateway request handler with HTTP proxy support
 */
public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static final AttributeKey<HttpClient> HTTP_CLIENT_KEY = AttributeKey.valueOf("httpClient");
    private static final Logger logger = LogManager.getLogger(GatewayHandler.class);
    private final GatewayConfig config;
    private final Router router;
    private final HttpClient httpClient;

    public GatewayHandler(GatewayConfig config, HttpClient httpClient) {
        this.config = config;
        this.router = new SimpleRouter(config);
        this.httpClient = httpClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String requestId = generateRequestId();
        long startTime = System.currentTimeMillis();

        // Add request ID to request headers
        request.headers().set("X-Request-Id", requestId);

        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Received request: {} {}", requestId, request.method(), request.uri());
        }

        try {
            // Handle health check
            if (isHealthCheck(request)) {
                handleHealthCheck(ctx, request, requestId, startTime);
                return;
            }

            // Route the request
            RouteResult routeResult = router.route(request);

            if (routeResult == null) {
                handleNotFound(ctx, request, requestId, startTime);
                return;
            }

            // Forward the request to backend via HTTP proxy
            forwardRequest(ctx, request, routeResult, requestId, startTime);

        } catch (Exception e) {
            logger.error("[{}] Error processing request", requestId, e);
            handleError(ctx, request, requestId, startTime, e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Unhandled exception", cause);
        ctx.close();
    }

    /**
     * Forward request to backend using HTTP client
     */
    private void forwardRequest(ChannelHandlerContext ctx, FullHttpRequest request,
                                RouteResult routeResult, String requestId, long startTime) {
        // Create gateway context
        String clientIp = getClientIp(request, ctx);
        GatewayContext gatewayContext = new GatewayContext(
                ctx, request, clientIp
        );
        gatewayContext.setRouteResult(routeResult);

        // Execute backend request
        CompletableFuture<FullHttpResponse> future = httpClient.execute(
                gatewayContext, routeResult.getTargetUri()
        );

        future.whenComplete((response, throwable) -> {
            if (throwable != null) {
                logger.error("[{}] Backend request failed: {}", requestId, throwable.getMessage());
                handleBackendError(ctx, request, requestId, startTime, throwable);
            } else {
                // Copy response to client
                FullHttpResponse clientResponse = copyResponse(response, requestId, startTime);
                sendResponse(ctx, request, clientResponse);
                response.release();
            }
        });
    }

    /**
     * Copy backend response for client
     */
    private FullHttpResponse copyResponse(FullHttpResponse backendResponse,
                                          String requestId, long startTime) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                backendResponse.protocolVersion(),
                backendResponse.status(),
                backendResponse.content().retainedDuplicate()
        );

        // Copy headers
        response.headers().set(backendResponse.headers());

        // Add gateway headers
        response.headers()
                .set("X-Request-Id", requestId)
                .set("X-Gateway-Time", String.valueOf(System.currentTimeMillis() - startTime));

        return response;
    }

    /**
     * Handle backend request error
     */
    private void handleBackendError(ChannelHandlerContext ctx, FullHttpRequest request,
                                    String requestId, long startTime, Throwable error) {
        HttpResponseStatus status = HttpResponseStatus.BAD_GATEWAY;
        String message = "Backend service unavailable";

        if (error instanceof java.util.concurrent.TimeoutException) {
            status = HttpResponseStatus.GATEWAY_TIMEOUT;
            message = "Backend service timeout";
        }

        String responseBody = String.format(
                "{\"error\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}",
                message, error.getMessage(), requestId
        );

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8)
        );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set("X-Request-Id", requestId)
                .set("X-Gateway-Time", String.valueOf(System.currentTimeMillis() - startTime));

        sendResponse(ctx, request, response);
    }

    // ... (保留原有的辅助方法)

    private boolean isHealthCheck(FullHttpRequest request) {
        String uri = request.uri();
        return uri.equals("/health") || uri.equals("/healthz");
    }

    private void handleHealthCheck(ChannelHandlerContext ctx, FullHttpRequest request,
                                   String requestId, long startTime) {
        String responseBody = String.format(
                "{\"status\":\"UP\",\"timestamp\":%d}",
                System.currentTimeMillis()
        );

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8)
        );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set("X-Request-Id", requestId)
                .set("X-Gateway-Time", String.valueOf(System.currentTimeMillis() - startTime));

        sendResponse(ctx, request, response);
    }

    private void handleNotFound(ChannelHandlerContext ctx, FullHttpRequest request,
                                String requestId, long startTime) {
        String responseBody = String.format(
                "{\"error\":\"Not Found\",\"message\":\"No route found for %s\",\"requestId\":\"%s\"}",
                request.uri(), requestId
        );

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND,
                Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8)
        );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set("X-Request-Id", requestId)
                .set("X-Gateway-Time", String.valueOf(System.currentTimeMillis() - startTime));

        sendResponse(ctx, request, response);
    }

    private void handleError(ChannelHandlerContext ctx, FullHttpRequest request,
                             String requestId, long startTime, Throwable error) {
        String responseBody = String.format(
                "{\"error\":\"Internal Server Error\",\"message\":\"%s\",\"requestId\":\"%s\"}",
                error.getMessage(), requestId
        );

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8)
        );

        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set("X-Request-Id", requestId)
                .set("X-Gateway-Time", String.valueOf(System.currentTimeMillis() - startTime));

        sendResponse(ctx, request, response);
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        // Handle keep-alive
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String generateRequestId() {
        return RandomUtil.uuidShort(16);
    }

    private String getClientIp(FullHttpRequest request, ChannelHandlerContext ctx) {
        String ip = request.headers().get("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.headers().get("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = ctx.channel().remoteAddress().toString();
        }
        return ip.split(",")[0].trim();
    }
}
