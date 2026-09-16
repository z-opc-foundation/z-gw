package com.zifang.z.gw.core.filter.proxy;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.ServiceInstance;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.server.GatewayHandler;
import com.zifang.z.gw.core.service.LbUriResolver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 代理过滤器 — 链上最晚执行(order=999),实际把请求转发到后端服务。
 *
 * <p>关键步骤:
 * <ol>
 *   <li>从 ctx 取 matchedRoute,解析 uri(lb:// → ServiceDiscovery + LoadBalancer)</li>
 *   <li>把 upstream path 应用 StripPrefix / RewritePath 等</li>
 *   <li>通过 BackendHttpClient 发送,异步等待响应</li>
 *   <li>把响应写回 Netty 客户端</li>
 * </ol>
 */
public class NettyProxyFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(NettyProxyFilter.class);
    public static final String NAME = "NettyProxy";

    private final BackendHttpClient backendClient;
    private final LbUriResolver uriResolver;

    public NettyProxyFilter() {
        this(new BackendHttpClient(new com.zifang.z.gw.core.config.ServerConfig()),
                LbUriResolver.defaults());
    }

    public NettyProxyFilter(BackendHttpClient backendClient, LbUriResolver uriResolver) {
        this.backendClient = backendClient;
        this.uriResolver = uriResolver;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 999;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        // 先推进 chain 让其他过滤器(StripPrefix 等)修改 ctx.path
        chain.filter(ctx);

        // 如果之前的过滤器已经写好响应或短路,跳过代理
        if (ctx.getTargetUri() == null && ctx.getMatchedRoute() == null) {
            return;
        }
        if (Boolean.TRUE.equals(ctx.getAttribute("proxy.skip"))) {
            return;
        }

        // 解析 target URI → upstream instance
        ServiceInstance instance;
        String upstreamPath;
        try {
            LbUriResolver.Resolved resolved = uriResolver.resolve(ctx);
            instance = resolved.instance;
            upstreamPath = resolved.path;
        } catch (GatewayException ge) {
            throw ge;
        } catch (Exception e) {
            log.error("[{}] URI resolve failed: {}", ctx.getRequestId(), e.getMessage());
            throw new GatewayException.BadGatewayException("Failed to resolve backend: " + e.getMessage(), e);
        }
        if (instance == null) {
            throw new GatewayException.BadGatewayException("No healthy backend instance for " + ctx.getMatchedRoute().getUri());
        }

        // 取 netty ctx 写响应
        ChannelHandlerContext nettyCtx = ctx.getAttribute("netty.ctx", ChannelHandlerContext.class);
        FullHttpRequest originalReq = ctx.getAttribute("req.original", FullHttpRequest.class);
        if (nettyCtx == null || originalReq == null) {
            log.warn("[{}] missing netty ctx, skip proxy writeback", ctx.getRequestId());
            return;
        }

        // 发送请求
        CompletableFuture<FullHttpResponse> future = backendClient.execute(ctx, instance, upstreamPath, ctx.getQuery());
        try {
            FullHttpResponse resp = future.get(30, TimeUnit.SECONDS);
            ctx.setAttribute("resp.status", String.valueOf(resp.status().code()));
            GatewayHandler.writeFullResponse(nettyCtx, originalReq, resp);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new GatewayException.GatewayTimeoutException("Backend timeout");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw new GatewayException.BadGatewayException("Backend failed: " + cause.getMessage(), cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GatewayException.BadGatewayException("Interrupted", ie);
        }
    }
}
