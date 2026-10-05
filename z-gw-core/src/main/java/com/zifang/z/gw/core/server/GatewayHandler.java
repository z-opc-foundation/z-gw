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
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 网关请求处理器 — Netty {@code SimpleChannelInboundHandler<FullHttpRequest>} 实现。
 *
 * <p>处理流程:
 * <ol>
 *   <li>解析 FullHttpRequest → 构造 GatewayContext (path/query/headers/body)</li>
 *   <li>调用 RouteMatcher 匹配路由</li>
 *   <li>未命中 → 404</li>
 *   <li>命中 → 组装过滤器链 (全局 + 路由级) → 投递到<b>业务线程池</b>执行</li>
 *   <li>最后过滤器(ProxyFilter)实际出站转发</li>
 *   <li>响应写回客户端</li>
 * </ol>
 *
 * <p><b>线程模型</b>：解析与路由匹配在 Netty EventLoop 上（不阻塞），过滤器链整体在
 * {@code ServerConfig.businessThread*} 定义的线程池上跑——链尾 {@code NettyProxyFilter}
 * 要阻塞等后端响应，EventLoop 被占住会让同一 EventLoop 上所有通道的读写与超时检测停摆。</p>
 */
public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(GatewayHandler.class);

    /**
     * 标记"本请求的响应已经写出"。
     * <p>链里"某个过滤器先写了响应、后一个又抛异常"是可能发生的；不设这道闸就会往同一条
     * 连接上连发两个 HTTP 响应（Netty 不会拦，是客户端先炸）。</p>
     */
    static final String RESP_WRITTEN = "resp.written";

    private final ServerConfig serverConfig;
    private final RouteMatcher routeMatcher;
    private final FilterAssembler filterAssembler;
    private final BackendHttpClient backendClient;
    /** 跑过滤器链用的业务线程池，按 {@code ServerConfig.businessThread*} 建。 */
    private final ThreadPoolExecutor businessExecutor;

    public GatewayHandler(ServerConfig serverConfig,
                          RouteMatcher routeMatcher,
                          FilterAssembler filterAssembler,
                          BackendHttpClient backendClient) {
        this.serverConfig = serverConfig;
        this.routeMatcher = routeMatcher;
        this.filterAssembler = filterAssembler;
        this.backendClient = backendClient;
        this.businessExecutor = newBusinessExecutor(serverConfig);
    }

    /**
     * 按 {@code ServerConfig} 的 businessThread* 建池子。
     *
     * <p>这三个参数此前只有声明和 getter/setter，全仓零读取点，而 {@link ServerConfig}
     * 上方注释就写着"业务线程池(阻塞操作放这里,避免占用 Netty EventLoop)"——即链尾那次
     * 阻塞等待正是被这条注释点名要搬走的东西。</p>
     *
     * <p>队列满用 {@link ThreadPoolExecutor.AbortPolicy}：直接拒并回错误响应，而不是
     * CallerRuns 把调用方（Netty EventLoop）拖回阻塞。</p>
     */
    private static ThreadPoolExecutor newBusinessExecutor(ServerConfig cfg) {
        ServerConfig c = cfg == null ? new ServerConfig() : cfg;
        int core = Math.max(1, c.getBusinessThreadCore());
        int max = Math.max(core, c.getBusinessThreadMax());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(Math.max(1, c.getBusinessQueue())),
                new DefaultThreadFactory("zgw-business", true),
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    /** 释放业务线程池；由 {@link GatewayServer#shutdown()} 调用。 */
    public void shutdown() {
        if (businessExecutor != null) {
            businessExecutor.shutdownNow();
        }
    }

    /**
     * 本 handler 业务线程池的当前线程数（包可见，供测试与监控观察池是否真按配置建）。
     * <p>不能靠线程名数——surefire 一个模块复用一个 JVM，同名池不止这一个。</p>
     */
    int businessPoolSize() {
        return businessExecutor == null ? 0 : businessExecutor.getPoolSize();
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
                // path 与 requestId 都来自请求方(path 走 URL 解码、requestId 直接取
                // X-Request-Id 头且无校验)，必须与下面两处一样过 escape()。
                writeJson(nettyCtx, request, 404,
                        "{\"error\":\"Not Found\",\"message\":\"No route matched " + escape(ctx.getPath())
                                + "\",\"requestId\":\"" + escape(ctx.getRequestId()) + "\"}");
                return;
            }
            ctx.setMatchedRoute(route);

            // 链里有阻塞操作（等后端响应），整条链挪到业务线程池执行
            try {
                businessExecutor.execute(() -> runFilterChain(ctx, request, route));
            } catch (RejectedExecutionException re) {
                log.warn("[{}] business pool saturated, core={} max={} queue={}",
                        ctx.getRequestId(), serverConfig.getBusinessThreadCore(),
                        serverConfig.getBusinessThreadMax(), serverConfig.getBusinessQueue());
                writeError(nettyCtx, request, new GatewayException.BadGatewayException(
                        "Gateway overloaded: business queue full"));
            }
        } catch (GatewayException ge) {
            writeError(nettyCtx, request, ge);
        } catch (Exception e) {
            writeInternalError(nettyCtx, request, ctx.getRequestId(), e);
        }
    }

    /**
     * 在业务线程池上执行过滤器链。
     *
     * <p>链上 {@code ErrorHandlingGlobalFilter}(order=900) 兜住它之后的过滤器；它<b>之前</b>
     * 的（Tracing/Metrics/Cors/Logging）抛出来会落到这里的 catch。异常不再抛回
     * {@code channelRead0}（那里早就返回了），必须就地转成响应。</p>
     */
    private void runFilterChain(GatewayContext ctx, FullHttpRequest request, RouteDefinition route) {
        try {
            filterAssembler.assemble(ctx, route).filter(ctx);
        } catch (GatewayException ge) {
            log.warn("[{}] GatewayException: status={} code={} msg={}",
                    ctx.getRequestId(), ge.getHttpStatus(), ge.getCode(), ge.getMessage());
            writeErrorOnce(ctx, request, ge);
        } catch (Exception e) {
            writeInternalErrorOnce(ctx, request, e);
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
        writeJson(nettyCtx, request, status, json, null);
    }

    /**
     * 写 JSON 响应，可附带额外响应头。
     *
     * @param extraHeaders 额外要写进响应的头；null 表示没有
     */
    public static void writeJson(ChannelHandlerContext nettyCtx, FullHttpRequest request, int status,
                                 String json, java.util.Map<String, String> extraHeaders) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                // 与 writeCorsOptions 声明的同一个策略。少了这一格，预检会过、而浏览器读不到实际响应体。
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                resp.headers().set(e.getKey(), e.getValue());
            }
        }

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            nettyCtx.writeAndFlush(resp);
        } else {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            nettyCtx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 写 {@link GatewayException} 对应的错误响应。
     *
     * <p>429 额外带 {@code Retry-After}：{@link RateLimiter.Result} 一直带着
     * {@code retryAfterSeconds}，{@link com.zifang.z.gw.api.GatewayException.RateLimitedException}
     * 也一直存着它，但此前<b>没有任何地方把它写进响应</b>——{@code RateLimiter} 接口
     * 注释承诺的"给 429 响应 Retry-After 头"一直没兑现，客户端只能自己猜退避。
     * 顺带修掉了令牌桶补充率为 0 时 {@code retryAfterSeconds} 变成 9223372036 的问题。</p>
     */
    public static void writeError(ChannelHandlerContext nettyCtx, FullHttpRequest request, GatewayException ge) {
        String json = "{\"error\":\"" + escape(ge.getCode()) + "\",\"message\":\"" + escape(ge.getMessage()) + "\"}";
        if (ge instanceof GatewayException.RateLimitedException) {
            long retry = ((GatewayException.RateLimitedException) ge).getRetryAfterSeconds();
            Map<String, String> extra = new HashMap<>(2);
            extra.put(HttpHeaderNames.RETRY_AFTER.toString(), Long.toString(Math.max(0L, retry)));
            writeJson(nettyCtx, request, ge.getHttpStatus(), json, extra);
        } else {
            writeJson(nettyCtx, request, ge.getHttpStatus(), json);
        }
    }

    /**
     * 兜底 500 —— {@link #channelRead0} 与业务线程上的链共用同一条写入路径，
     * 共用 {@link #escape}（消息里的控制字符原样进 JSON 是非法的）。
     */
    public static void writeInternalError(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                          String requestId, Throwable cause) {
        log.error("[{}] Gateway error", requestId, cause);
        writeJson(nettyCtx, request, 500,
                "{\"error\":\"Internal Server Error\",\"message\":\"" + escape(cause.getMessage()) + "\"}");
    }

    /**
     * 写 {@link GatewayException} 响应，同一请求只写一次。
     *
     * <p>供已经离开 {@code channelRead0} 的调用方使用（业务线程上的过滤器链、
     * {@code ErrorHandlingGlobalFilter}）：那条路上 {@link #writeError} 不设闸，
     * 而"先写了响应、后一个过滤器又抛异常"会往同一条连接上连发两个 HTTP 响应。</p>
     *
     * @return true 表示本次确实写了
     */
    public static boolean writeErrorOnce(GatewayContext ctx, FullHttpRequest request, GatewayException ge) {
        ChannelHandlerContext nettyCtx = ctx.getAttribute("netty.ctx", ChannelHandlerContext.class);
        if (nettyCtx == null || !claimResponse(ctx)) {
            return false;
        }
        writeError(nettyCtx, request, ge);
        return true;
    }

    /** 兜底 500 的同门版本，同一请求只写一次。 */
    public static boolean writeInternalErrorOnce(GatewayContext ctx, FullHttpRequest request, Throwable cause) {
        ChannelHandlerContext nettyCtx = ctx.getAttribute("netty.ctx", ChannelHandlerContext.class);
        if (nettyCtx == null || !claimResponse(ctx)) {
            return false;
        }
        writeInternalError(nettyCtx, request, ctx.getRequestId(), cause);
        return true;
    }

    /** 抢占"本请求响应尚未写出"的名额；抢到返回 true。 */
    public static boolean claimResponse(GatewayContext ctx) {
        if (Boolean.TRUE.equals(ctx.getAttribute(RESP_WRITTEN))) {
            return false;
        }
        ctx.setAttribute(RESP_WRITTEN, Boolean.TRUE);
        return true;
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
        // 浏览器读的是这一份实际响应上的 ACAO，不是预检那份。此前只有 writeCorsOptions 设过，
        // 于是预检 204 正常、正式请求也 200，但浏览器照样拦掉响应体。
        // ⚠ 用 contains 判断而不是直接 set：后端若自己显式给了 ACAO，那是那个服务的策略，网关不覆盖。
        if (!client.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)) {
            client.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            client.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            nettyCtx.writeAndFlush(client);
        } else {
            client.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            nettyCtx.writeAndFlush(client).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * JSON 字符串值转义。
     * <p>必须覆盖 JSON 规范要求转义的全部控制字符（U+0000–U+001F）：
     * 原来的链式 {@code replace} 只处理 {@code " \\ \n \r}，漏掉 {@code \t \b \f}
     * 及其余控制字符，而它们<b>原样出现在 JSON 里是非法的</b>。path 走
     * {@code QueryStringDecoder} 会做 URL 解码，{@code %09} 就是 tab。</p>
     */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
