package com.zgw.core.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.zifang.util.core.lang.RandomUtil;

/**
 * Gateway context for request processing
 */
public class GatewayContext {

    private final String requestId;
    private final ChannelHandlerContext ctx;
    private final FullHttpRequest request;
    private final String clientIp;
    private final long startTime;
    private final Map<String, Object> attributes;
    private FullHttpResponse response;
    private Route route;
    private Throwable error;

    public GatewayContext(ChannelHandlerContext ctx, FullHttpRequest request, String clientIp) {
        this.requestId = generateRequestId();
        this.ctx = ctx;
        this.request = request;
        this.clientIp = clientIp;
        this.startTime = System.currentTimeMillis();
        this.attributes = new HashMap<>();
    }

    private String generateRequestId() {
        return RandomUtil.uuidShort(16);
    }

    // Getters
    public String getRequestId() {
        return requestId;
    }

    public ChannelHandlerContext getCtx() {
        return ctx;
    }

    public FullHttpRequest getRequest() {
        return request;
    }

    public String getClientIp() {
        return clientIp;
    }

    // Setter for client IP - used by GatewayHandler
    public void setClientIp(String clientIp) {
        // This method exists for API compatibility with GatewayHandler
        // The actual clientIp field is final, so we can't change it
        // But we can store it as an attribute
        setAttribute("clientIpOverride", clientIp);
    }

    public long getStartTime() {
        return startTime;
    }

    public FullHttpResponse getResponse() {
        return response;
    }

    public void setResponse(FullHttpResponse response) {
        this.response = response;
    }

    public Route getRoute() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route;
    }

    // Alias for setRoute - used by GatewayHandler
    public void setRouteResult(com.zgw.core.router.RouteResult routeResult) {
        if (routeResult != null) {
            this.route = new Route(
                    routeResult.getRouteId(),
                    routeResult.getPath(),
                    routeResult.getBackend(),
                    routeResult.getTargetUri()
            );
        }
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    // Attribute methods
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        Object value = attributes.get(key);
        return value != null ? (T) value : defaultValue;
    }

    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /**
     * Route information
     */
    public static class Route {
        private final String id;
        private final String path;
        private final String backend;
        private final String targetUri;

        public Route(String id, String path, String backend, String targetUri) {
            this.id = id;
            this.path = path;
            this.backend = backend;
            this.targetUri = targetUri;
        }

        public String getId() {
            return id;
        }

        public String getPath() {
            return path;
        }

        public String getBackend() {
            return backend;
        }

        public String getTargetUri() {
            return targetUri;
        }
    }
}