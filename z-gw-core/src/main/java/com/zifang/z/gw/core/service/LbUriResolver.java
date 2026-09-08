package com.zifang.z.gw.core.service;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.ServiceDiscovery;
import com.zifang.z.gw.api.ServiceInstance;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * URI 解析器 — 把路由配置的 uri 解析为上游实例和路径。
 *
 * <p>支持 3 种 URI scheme:
 * <ul>
 *   <li>{@code http://host:port} — 直接 HTTP 转发</li>
 *   <li>{@code lb://serviceName} — 通过 ServiceDiscovery 查找实例 + LoadBalancer 选一</li>
 *   <li>{@code forward://localPath} — 转发到网关本地的 Spring 控制器(预留)</li>
 * </ul>
 */
public class LbUriResolver {

    public static final String SCHEME_LB = "lb";
    public static final String SCHEME_HTTP = "http";
    public static final String SCHEME_HTTPS = "https";
    public static final String SCHEME_FORWARD = "forward";

    /** scheme=http(s)://host:port 静态单实例 */
    private static final Map<String, ServiceInstance> STATIC_CACHE = new ConcurrentHashMap<>();

    private final ServiceDiscovery serviceDiscovery;
    private final com.zifang.z.gw.api.LoadBalancer loadBalancer;

    public LbUriResolver(ServiceDiscovery serviceDiscovery,
                         com.zifang.z.gw.api.LoadBalancer loadBalancer) {
        this.serviceDiscovery = serviceDiscovery;
        this.loadBalancer = loadBalancer;
    }

    public static LbUriResolver defaults() {
        return new LbUriResolver(
                new com.zifang.z.gw.core.service.discovery.StaticServiceDiscovery(),
                new com.zifang.z.gw.core.lb.RoundRobinLoadBalancer()
        );
    }

    public Resolved resolve(GatewayContext ctx) {
        String uriStr = ctx.getMatchedRoute().getUri();
        URI uri;
        try {
            uri = URI.create(uriStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid route uri: " + uriStr, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null) scheme = SCHEME_HTTP;

        switch (scheme.toLowerCase()) {
            case SCHEME_HTTP:
            case SCHEME_HTTPS:
                return resolveStatic(uri);
            case SCHEME_LB:
                return resolveByLoadBalancer(uri.getHost(), ctx);
            case SCHEME_FORWARD:
                // forward://xxx 模式:不做上游转发,直接转发到本地 path
                Resolved r = new Resolved();
                r.instance = null;
                r.path = uri.getHost() != null ? "/" + uri.getHost() : "/";
                return r;
            default:
                throw new IllegalArgumentException("Unsupported uri scheme: " + scheme);
        }
    }

    private Resolved resolveStatic(URI uri) {
        Resolved r = new Resolved();
        String key = uri.toString();
        ServiceInstance ins = STATIC_CACHE.computeIfAbsent(key, k ->
                ServiceInstance.builder()
                        .serviceId(uri.getHost())
                        .instanceId(uri.getHost() + ":" + uri.getPort())
                        .host(uri.getHost())
                        .port(uri.getPort() == -1 ? 80 : uri.getPort())
                        .build());
        r.instance = ins;
        r.path = uri.getPath() != null ? uri.getPath() : "/";
        return r;
    }

    private Resolved resolveByLoadBalancer(String serviceId, GatewayContext ctx) {
        if (serviceDiscovery == null) {
            throw new IllegalStateException("No ServiceDiscovery configured for lb:// routing");
        }
        List<ServiceInstance> all = serviceDiscovery.getHealthyInstances(serviceId);
        if (all == null || all.isEmpty()) {
            throw new IllegalStateException("No healthy instances for service: " + serviceId);
        }
        ServiceInstance picked = loadBalancer == null
                ? all.get(0)
                : loadBalancer.select(serviceId, all, ctx);
        Resolved r = new Resolved();
        r.instance = picked;
        r.path = ctx.getPath();  // lb 模式下保留原始路径
        return r;
    }

    /** 解析结果 */
    public static class Resolved {
        public ServiceInstance instance;
        public String path;
    }
}
