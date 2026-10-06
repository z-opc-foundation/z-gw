package com.zifang.z.gw.core.server;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.CorsPolicy;
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
import io.netty.util.NetUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /**
     * 出站 CORS 策略，由 {@code ServerConfig} 的 {@code cors-*} 五个字段派生。
     * <p>配置是启动时读的，所以构造时算一次；逐请求重解析逗号列表纯属浪费。</p>
     */
    private final CorsPolicy corsPolicy;

    public GatewayHandler(ServerConfig serverConfig,
                          RouteMatcher routeMatcher,
                          FilterAssembler filterAssembler,
                          BackendHttpClient backendClient) {
        this.serverConfig = serverConfig;
        this.routeMatcher = routeMatcher;
        this.filterAssembler = filterAssembler;
        this.backendClient = backendClient;
        this.corsPolicy = CorsPolicy.from(serverConfig);
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
                writeJson(nettyCtx, request, 200, "{\"status\":\"UP\"}", null, corsPolicy);
                return;
            }

            // OPTIONS 预检。**只在跨域已开启时才拦**：
            // corsEnabled 默认 false，而此前这一段是无条件的 —— 声明"默认关闭跨域"，
            // 实际是网关对任意来源一律回 204 + ACAO: *。关掉跨域时 OPTIONS 不该被网关
            // 吞掉，走正常的路由匹配（转给后端，或如实回 404）。
            if (corsPolicy.isEnabled() && "OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
                writeCorsOptions(nettyCtx, request, corsPolicy);
                return;
            }

            // 路由匹配
            RouteDefinition route = routeMatcher.route(ctx);
            if (route == null) {
                // path 与 requestId 都来自请求方(path 走 URL 解码、requestId 直接取
                // X-Request-Id 头且无校验)，必须与下面两处一样过 escape()。
                writeJson(nettyCtx, request, 404,
                        "{\"error\":\"Not Found\",\"message\":\"No route matched " + escape(ctx.getPath())
                                + "\",\"requestId\":\"" + escape(ctx.getRequestId()) + "\"}", null, corsPolicy);
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
                        "Gateway overloaded: business queue full"), corsPolicy);
            }
        } catch (GatewayException ge) {
            writeError(nettyCtx, request, ge, corsPolicy);
        } catch (Exception e) {
            writeInternalError(nettyCtx, request, ctx.getRequestId(), e, corsPolicy);
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
        // CORS 策略挂上：writeErrorOnce / writeInternalErrorOnce / NettyProxyFilter
        // 都在链上，只能经 ctx 拿到"本次请求该用哪套 CORS 头"。
        ctx.setAttribute(CorsPolicy.ATTR, corsPolicy);

        // 解析 path & query
        QueryStringDecoder dec = new QueryStringDecoder(request.uri());
        ctx.setPath(dec.path());
        ctx.setQuery(dec.rawQuery());

        // 客户端 IP
        ctx.setClientIp(resolveClientIp(request, serverConfig.getTrustedProxyHops(),
                nettyCtx.channel() == null ? null : nettyCtx.channel().remoteAddress()));

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

    // === 客户端 IP ===

    /**
     * 解析客户端 IP。
     *
     * <p><b>原来这里是 {@code xff.split(",")[0]}</b> —— {@code X-Forwarded-For} 的<b>最左</b>段，
     * 而且是无条件采信、没有任何开关能关。那一段是整条链路上<b>最没有约束力</b>的一段：
     * 任何能连到网关的客户端自己填一个 {@code X-Forwarded-For: 1.2.3.4}，
     * {@code clientIp} 就是 1.2.3.4。而 {@code clientIp} 在本仓有四个出口 ——
     * 限流键（{@code RateLimitFilterFactory}）、IP hash 负载均衡（{@code IpHashLoadBalancer}）、
     * 原样写进转发给后端的 {@code X-Forwarded-For}（{@code BackendHttpClient}）、访问日志。
     * 也就是"客户端自选限流键 + 把任意字符串注入内网请求头 + 把所有流量 hash 到同一个后端"。</p>
     *
     * <p>现在：默认 {@code trustedProxyHops = 0}，<b>不采信任何请求头</b>，只用 TCP 远端地址。
     * 网关前面确实挂了 N 层自有代理时才设成 N，那时从 XFF <b>右往左</b>数第 N 段 ——
     * 每一跳代理都把自己看到的来源 append 到链尾，所以越靠右越接近网关、越可信。</p>
     *
     * <p>任何"取不到就回落"的分支都回落到 TCP 远端地址：那是唯一由内核给出、客户端改不了的值。</p>
     *
     * <p>{@code static} + 包可见是为了能被直接钉住（不造 {@code ChannelHandlerContext}、不反射）；
     * 逻辑本身不依赖 handler 实例。</p>
     */
    static String resolveClientIp(FullHttpRequest request, int trustedProxyHops, SocketAddress remote) {
        String remoteAddr = remoteAddressOf(remote);
        int hops = trustedProxyHops;
        if (hops <= 0) {
            return remoteAddr;
        }
        String xff = request.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            String[] parts = xff.split(",");
            if (parts.length < hops) {
                // 链比配置的短：有某一跳没登记，或者有人在直连网关。按不可信处理。
                if (WARNED_SHORT_XFF.compareAndSet(false, true)) {
                    log.warn("X-Forwarded-For 只有 {} 段，少于配置的可信代理跳数 {}，已回落到 TCP 远端地址。"
                                    + "（有代理没登记就把 zgw.server.trusted-proxy-hops 调小；"
                                    + "本进程只提示这一次）",
                            parts.length, hops);
                }
                return remoteAddr;
            }
            String candidate = parts[parts.length - hops].trim();
            // 必须是像样的 IP 字面量才认：clientIp 会进日志、限流键和转发给后端的头，
            // 一段任意字符串进来就是往这些地方塞不受控内容。
            if (NetUtil.isValidIpV4Address(candidate) || NetUtil.isValidIpV6Address(candidate)) {
                return candidate;
            }
            if (WARNED_BAD_XFF.compareAndSet(false, true)) {
                log.warn("X-Forwarded-For 从右数第 {} 段不是合法 IP（长度 {}），已回落到 TCP 远端地址。"
                        + "（本进程只提示这一次）", hops, candidate.length());
            }
            return remoteAddr;
        }
        if (hops == 1) {
            // X-Real-IP 只能代表"最靠近网关的那一跳看到的来源"，也就是第 1 跳。
            // hops > 1 时它没有那么多信息，拿它当第 N 跳会把内网代理的地址当成客户端 IP，
            // 所以那种情况下直接回落，不猜。
            String xri = request.headers().get("X-Real-IP");
            if (xri != null) {
                String trimmed = xri.trim();
                if (NetUtil.isValidIpV4Address(trimmed) || NetUtil.isValidIpV6Address(trimmed)) {
                    return trimmed;
                }
                if (WARNED_BAD_XRI.compareAndSet(false, true)) {
                    log.warn("X-Real-IP 不是合法 IP（长度 {}），已回落到 TCP 远端地址。（本进程只提示这一次）",
                            trimmed.length());
                }
            }
        }
        return remoteAddr;
    }

    /**
     * TCP 远端地址。
     *
     * <p>原来是 {@code remoteAddress().toString()} 再"去掉开头的 /、截断到第一个冒号"，
     * 对 IPv6 会算出 {@code "/[::1]:9090"} → {@code "["} —— 也就是说 <b>IPv6 客户端的
     * clientIp 是个单字符的 {@code "["}</b>，限流按它分组时所有 IPv6 客户端挤成一组。
     * 走 {@link InetSocketAddress#getHostAddress()} 拿字面量，IPv4/IPv6 都对。</p>
     */
    private static String remoteAddressOf(SocketAddress addr) {
        if (addr instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) addr;
            InetAddress resolved = inet.getAddress();
            // 未解析的地址 getAddress() 为 null，这时 getHostString() 至少还是主机名而非 "["
            return resolved != null ? resolved.getHostAddress() : inet.getHostString();
        }
        return addr == null ? "" : addr.toString();
    }

    // 配错一次提示一次就够：这里是网关，任何人都能发请求，每请求一条 warn 就是在往磁盘里灌水。
    // 配置是启动时读的，改配置本来就得重启，所以不会漏报。
    private static final AtomicBoolean WARNED_SHORT_XFF = new AtomicBoolean();
    private static final AtomicBoolean WARNED_BAD_XFF = new AtomicBoolean();
    private static final AtomicBoolean WARNED_BAD_XRI = new AtomicBoolean();

    private boolean isHealthPath(String path) {
        return "/health".equals(path) || "/healthz".equals(path) || "/".equals(path);
    }

    // === 写响应 ===

    public static void writeJson(ChannelHandlerContext nettyCtx, FullHttpRequest request, int status, String json) {
        writeJson(nettyCtx, request, status, json, null, CorsPolicy.DISABLED);
    }

    /**
     * 写 JSON 响应，可附带额外响应头。
     *
     * @param extraHeaders 额外要写进响应的头；null 表示没有
     */
    public static void writeJson(ChannelHandlerContext nettyCtx, FullHttpRequest request, int status,
                                 String json, java.util.Map<String, String> extraHeaders) {
        writeJson(nettyCtx, request, status, json, extraHeaders, CorsPolicy.DISABLED);
    }

    /**
     * @param cors 出站 CORS 策略；{@link CorsPolicy#DISABLED} 表示一个 CORS 头都不写
     */
    public static void writeJson(ChannelHandlerContext nettyCtx, FullHttpRequest request, int status,
                                 String json, java.util.Map<String, String> extraHeaders, CorsPolicy cors) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        // 与预检声明的同一个策略。少了这一格，预检会过、而浏览器读不到实际响应体。
        cors.applyActual(resp.headers(), request.headers().get(HttpHeaderNames.ORIGIN));
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
        writeError(nettyCtx, request, ge, CorsPolicy.DISABLED);
    }

    public static void writeError(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                  GatewayException ge, CorsPolicy cors) {
        String json = "{\"error\":\"" + escape(ge.getCode()) + "\",\"message\":\"" + escape(ge.getMessage()) + "\"}";
        if (ge instanceof GatewayException.RateLimitedException) {
            long retry = ((GatewayException.RateLimitedException) ge).getRetryAfterSeconds();
            Map<String, String> extra = new HashMap<>(2);
            extra.put(HttpHeaderNames.RETRY_AFTER.toString(), Long.toString(Math.max(0L, retry)));
            writeJson(nettyCtx, request, ge.getHttpStatus(), json, extra, cors);
        } else {
            writeJson(nettyCtx, request, ge.getHttpStatus(), json, null, cors);
        }
    }

    /**
     * 兜底 500 —— {@link #channelRead0} 与业务线程上的链共用同一条写入路径，
     * 共用 {@link #escape}（消息里的控制字符原样进 JSON 是非法的）。
     */
    public static void writeInternalError(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                          String requestId, Throwable cause) {
        writeInternalError(nettyCtx, request, requestId, cause, CorsPolicy.DISABLED);
    }

    public static void writeInternalError(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                          String requestId, Throwable cause, CorsPolicy cors) {
        log.error("[{}] Gateway error", requestId, cause);
        writeJson(nettyCtx, request, 500,
                "{\"error\":\"Internal Server Error\",\"message\":\"" + escape(cause.getMessage()) + "\"}",
                null, cors);
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
        writeError(nettyCtx, request, ge, policyOf(ctx));
        return true;
    }

    /** 兜底 500 的同门版本，同一请求只写一次。 */
    public static boolean writeInternalErrorOnce(GatewayContext ctx, FullHttpRequest request, Throwable cause) {
        ChannelHandlerContext nettyCtx = ctx.getAttribute("netty.ctx", ChannelHandlerContext.class);
        if (nettyCtx == null || !claimResponse(ctx)) {
            return false;
        }
        writeInternalError(nettyCtx, request, ctx.getRequestId(), cause, policyOf(ctx));
        return true;
    }

    /**
     * 从 ctx 上取本次请求的 CORS 策略。
     *
     * <p>{@link GatewayContext} 可能不是本类构造的（单测直接 new、或别的宿主塞进来），
     * 那种情况下没有这个 attribute —— 按"不写 CORS 头"处理，与 {@code corsEnabled}
     * 的默认值一致。</p>
     */
    public static CorsPolicy policyOf(GatewayContext ctx) {
        CorsPolicy p = ctx == null ? null : ctx.getAttribute(CorsPolicy.ATTR, CorsPolicy.class);
        return p == null ? CorsPolicy.DISABLED : p;
    }

    /** 抢占"本请求响应尚未写出"的名额；抢到返回 true。 */
    public static boolean claimResponse(GatewayContext ctx) {
        if (Boolean.TRUE.equals(ctx.getAttribute(RESP_WRITTEN))) {
            return false;
        }
        ctx.setAttribute(RESP_WRITTEN, Boolean.TRUE);
        return true;
    }

    /**
     * 写预检响应。
     *
     * <p>此前 {@code ACAO: *} / {@code ACAM} / {@code ACAH} / {@code ACMA} 全部写死，
     * {@code ServerConfig} 上对应的 {@code corsAllowedMethods} / {@code corsAllowedHeaders} /
     * {@code corsMaxAge} 三个字段<b>零读取点</b>，README 却写着「{@code zgw.server.cors-*}
     * 参数」。现在都从策略出。</p>
     */
    public static void writeCorsOptions(ChannelHandlerContext nettyCtx, FullHttpRequest request) {
        writeCorsOptions(nettyCtx, request, CorsPolicy.DISABLED);
    }

    public static void writeCorsOptions(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                        CorsPolicy policy) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        policy.applyPreflight(resp.headers(), request.headers().get(HttpHeaderNames.ORIGIN));
        nettyCtx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    public static void writeFullResponse(ChannelHandlerContext nettyCtx, FullHttpRequest request, FullHttpResponse backendResp) {
        writeFullResponse(nettyCtx, request, backendResp, null);
    }
    /**
     * 写回后端响应，可附带路由级过滤器声明的额外响应头。
     *
     * <p>{@code extraHeaders} 即 {@code AddResponseHeader} 过滤器写进 ctx 的
     * {@code resp.headers} attribute。此前该 attribute <b>全仓无人读取</b> ——
     * 三参版 {@link #writeFullResponse} 拿不到 ctx，也就无从合并，而过滤器自己的
     * Javadoc 写着「由 GatewayHandler.writeFullResponse 时合并」。
     * {@code YamlRouteLoader.defaultRoutes()} 的演示路由
     * {@code AddResponseHeader=X-Gateway, z-gw-demo} 因此从未出现在任何响应上。</p>
     *
     * <p>合并放在 ACAO 兜底之后。这不是为了让它压过 ACAO 兜底（那样的话把这段挪到兜底
     * 前面结果完全一样 —— {@code contains} 判断天然是"谁先写谁算数"），而是为了读代码时
     * 一眼能看出顺序：先给后端/默认策略留位置，再落路由级显式配置。兜底那个
     * {@code contains} 是为了尊重后端自己显式给出的 CORS 策略，不该把网关自己的配置
     * 也挡在外面。优先级与请求方向 {@code BackendHttpClient.copyEndToEndHeaders} 的
     * {@code target.set(...)} 一致：显式过滤器 &gt; 后端。</p>
     *
     * @param extraHeaders 额外要写进响应的头；null 表示没有
     */
    public static void writeFullResponse(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                         FullHttpResponse backendResp, Map<String, String> extraHeaders) {
        writeFullResponse(nettyCtx, request, backendResp, extraHeaders, CorsPolicy.DISABLED);
    }

    /**
     * @param cors 出站 CORS 策略；{@link CorsPolicy#DISABLED} 表示不补 ACAO
     */
    public static void writeFullResponse(ChannelHandlerContext nettyCtx, FullHttpRequest request,
                                         FullHttpResponse backendResp, Map<String, String> extraHeaders,
                                         CorsPolicy cors) {
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
            cors.applyActual(client.headers(), request.headers().get(HttpHeaderNames.ORIGIN));
        }
        // AddResponseHeader 等过滤器声明的头，最后落地（优先级最高）
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                client.headers().set(e.getKey(), e.getValue());
            }
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
