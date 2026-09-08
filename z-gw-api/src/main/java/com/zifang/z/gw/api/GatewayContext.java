package com.zifang.z.gw.api;

/**
 * 网关上下文 — 单次请求处理过程中所有组件共享的"上下文对象"。
 *
 * <p>设计为可变结构,各阶段组件往里放东西:
 * <ul>
 *   <li>Netty handler: 写入 uri/method/headers/clientIp/requestId</li>
 *   <li>路由匹配阶段: 写入 matchedRoute</li>
 *   <li>过滤器链: 读取 matchedRoute、写任意 attribute (例如 userId / traceId)</li>
 *   <li>HTTP 出站: 读取所有上下文组装出站请求</li>
 * </ul>
 *
 * <p>替代 z-gw v1 的 {@code GatewayContext} — v1 把 Netty 的 {@link io.netty.channel.ChannelHandlerContext}
 * 直接塞进 POJO,导致 Netty API 渗透到所有过滤器,v2 改为纯数据容器 + Netty API 显式生命周期管理。
 */
public class GatewayContext {

    private String requestId;
    private String method;
    private String uri;
    private String path;
    private String query;
    private String clientIp;
    private String host;

    private RouteDefinition matchedRoute;
    private String targetUri;

    /** 任意 attribute — 过滤器间传递状态用,key 命名建议加前缀避免冲突 (e.g. {@code auth.userId}) */
    private final java.util.Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

    // === 构造与基础访问 ===

    public GatewayContext() {
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public RouteDefinition getMatchedRoute() { return matchedRoute; }
    public void setMatchedRoute(RouteDefinition matchedRoute) { this.matchedRoute = matchedRoute; }

    public String getTargetUri() { return targetUri; }
    public void setTargetUri(String targetUri) { this.targetUri = targetUri; }

    // === Attributes ===

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object v = attributes.get(key);
        return type.isInstance(v) ? (T) v : null;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object removeAttribute(String key) {
        return attributes.remove(key);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }
}
