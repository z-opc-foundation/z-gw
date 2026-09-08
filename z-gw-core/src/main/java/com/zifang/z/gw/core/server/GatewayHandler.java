package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关请求处理器 — Netty {@code SimpleChannelInboundHandler<FullHttpRequest>} 实现。
 *
 * <p>处理流程:
 * <ol>
 *   <li>解析 FullHttpRequest → 构造 GatewayContext (path/query/headers/body)</li>
 *   <li>调用 RouteMatcher 匹配路由</li>
 *   <li>未命中 → 404</li>
 *   <li>命中 → 组装过滤器链 (全局 + 路由级) → 执行</li>
 *   <li>最后过滤器(ProxyFilter)实际出站转发</li>
 *   <li>响应写回客户端</li>
 * </ol>
 */
public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(GatewayHandler.class);

    private final ServerConfig serverConfig;
    private final RouteMatcher routeMatcher;
    private final FilterAssembler filterAssembler;
    private final BackendHttpClient backendClient;

    public GatewayHandler(ServerConfig serverConfig,
                          RouteMatcher routeMatcher,
                          FilterAssembler filterAssembler,
                          BackendHttpClient backendClient) {
        this.serverConfig = serverConfig;
        this.routeMatcher = routeMatcher;
        this.filterAssembler = filterAssembler;
        this.backendClient = backendClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext nettyCtx, FullHttpRequest request) throws Exception {
        GatewayContext ctx = buildContext(nettyCtx, request);

        try {
            // 健康检查直通
            if (isHealthPath(ctx.getPath())) {
                writeJson(nettyCtx, request, 200, "{\"status\":\"UP\"}");
                return;
            }

            // OPTIONS 预检
            if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
                writeCorsOptions(nettyCtx, request);
                return;
            }

            // 路由匹配
            RouteDefinition route = routeMatcher.route(ctx);
            if (route == null) {
                writeJson(nettyCtx, request, 404,
                        "{\"error\":\"Not Found\",\"message\":\"No route matched " + ctx.getPath() + "\",\"requestId\":\"" + ctx.getRequestId() + "\"}");
                return;
            }
            ctx.setMatchedRoute(route);

            // 组装过滤器链并执行
            filterAssembler.assemble(ctx, route).filter(ctx);
        } catch (GatewayException ge) {
            writeError(nettyCtx, request, ge);
        } catch (Exception e) {
            log.error("[{}] Gateway error", ctx.getRequestId(), e);
            writeJson(nettyCtx, request, 500,
                    "{\"error\":\"Internal Server Error\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext nettyCtx, Throwable cause) {
        log.error("Unhandled channel exception", cause);
        nettyCtx.close();
    }

    // === 上下文构造 ===

    private GatewayContext buildContext(ChannelHandlerContext nettyCtx, FullHttpRequest request) {
        GatewayContext ctx = new GatewayContext();

        // requestId
        String reqId = request.headers().get("X-Request-Id");
        if (reqId == null || reqId.isEmpty()) {
            reqId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        ctx.setRequestId(reqId);
        ctx.setMethod(request.method().name());
        ctx.setUri(request.uri());
        ctx.setHost(request.headers().get(HttpHeaderNames.HOST));

        // 解析 path & query
        QueryStringDecoder dec = new QueryStringDecoder(request.uri());
        ctx.setPath(dec.path());
        ctx.setQuery(dec.rawQuery());

        // 客户端 IP
        String xff = request.headers().get("X-Forwarded-For");
        String xri = request.headers().get("X-Real-IP");
        if (xff != null && !xff.isEmpty()) {
            ctx.setClientIp(xff.split(",")[0].trim());
        } else if (xri != null && !xri.isEmpty()) {
            ctx.setClientIp(xri);
        } else {
            String remote = nettyCtx.channel().remoteAddress() != null
                    ? nettyCtx.channel().remoteAddress().toString()
                    : "";
            if (remote.startsWith("/")) remote = remote.substring(1);
            int colon = remote.indexOf(':');
            ctx.setClientIp(colon > 0 ? remote.substring(0, colon) : remote);
        }

        // headers 拷贝到 attribute
        Map<String, String> hdrs = new HashMap<>();
        for (Map.Entry<String, String> e : request.headers().entries()) {
            hdrs.put(e.getKey(), e.getValue());
        }
        ctx.setAttribute("req.headers", hdrs);
        for (Map.Entry<String, String> e : request.headers().entries()) {
            ctx.setAttribute("req.header." + e.getKey().toLowerCase(), e.getValue());
        }

        // body bytes
        if (request.content().readableBytes() > 0) {
            byte[] body = new byte[request.content().readableBytes()];
            request.content().getBytes(0, body);
            ctx.setAttribute("req.body.bytes", body);
        }

        // 保存 netty ctx (供出站响应写回)
        ctx.setAttribute("netty.ctx", nettyCtx);
        ctx.setAttribute("req.original", request);

        return ctx;
    }

    private boolean isHealthPath(String path) {
        return "/health".equals(path) || "/healthz".equals(path) || "/".equals(path);
    }

    // === 写响应 ===

    public static void writeJson(ChannelHandlerContext nettyCtx, FullHttpRequest request, int status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            nettyCtx.writeAndFlush(resp);
        } else {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            nettyCtx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public static void writeError(ChannelHandlerContext nettyCtx, FullHttpRequest request, GatewayException ge) {
        writeJson(nettyCtx, request, ge.getHttpStatus(),
                "{\"error\":\"" + escape(ge.getCode()) + "\",\"message\":\"" + escape(ge.getMessage()) + "\"}");
    }

    public static void writeCorsOptions(ChannelHandlerContext nettyCtx, FullHttpRequest request) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        resp.headers()
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS,PATCH")
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                .set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "3600");
        nettyCtx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    public static void writeFullResponse(ChannelHandlerContext nettyCtx, FullHttpRequest request, FullHttpResponse backendResp) {
        // 保留 backend content 但设置新 status
        DefaultFullHttpResponse client = new DefaultFullHttpResponse(
                backendResp.protocolVersion(),
                backendResp.status(),
                backendResp.content().retainedDuplicate()
        );
        client.headers().set(backendResp.headers());
        // 去掉 hop-by-hop headers
        client.headers().remove("Transfer-Encoding");
        client.headers().set(HttpHeaderNames.CONTENT_LENGTH, client.content().readableBytes());

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            client.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            nettyCtx.writeAndFlush(client);
        } else {
            client.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            nettyCtx.writeAndFlush(client).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
