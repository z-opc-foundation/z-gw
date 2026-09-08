package com.zifang.z.gw.api;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务实例 — 表示一个后端服务节点,用于负载均衡。
 *
 * <p>{@code host:port} 是网络层地址;{@code metadata} 用于灰度/权重等扩展能力。
 *
 * <p>{@code activeConnections} 是可变状态,用于最少连接数负载均衡统计。
 */
public final class ServiceInstance {

    private final String serviceId;
    private final String instanceId;
    private final String host;
    private final int port;
    private final boolean healthy;
    private final int weight;
    private final List<String> tags;
    private final java.util.Map<String, String> metadata;

    /** 当前活跃连接数(可变,用于 LeastConnectionsLoadBalancer) */
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private ServiceInstance(Builder b) {
        this.serviceId = b.serviceId;
        this.instanceId = b.instanceId;
        this.host = b.host;
        this.port = b.port;
        this.healthy = b.healthy;
        this.weight = b.weight;
        this.tags = b.tags == null ? Collections.emptyList() : Collections.unmodifiableList(b.tags);
        this.metadata = b.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.metadata));
    }

    public String getServiceId() { return serviceId; }
    public String getInstanceId() { return instanceId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isHealthy() { return healthy; }
    public int getWeight() { return weight; }
    public List<String> getTags() { return tags; }
    public java.util.Map<String, String> getMetadata() { return metadata; }

    public String address() {
        return host + ":" + port;
    }

    public String url(String scheme) {
        return scheme + "://" + address();
    }

    /** 当前活跃连接数(用于 LeastConnectionsLoadBalancer) */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /** 增加活跃连接计数 */
    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }

    /** 减少活跃连接计数 */
    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String serviceId;
        private String instanceId;
        private String host;
        private int port;
        private boolean healthy = true;
        private int weight = 100;
        private List<String> tags;
        private java.util.Map<String, String> metadata;

        public Builder serviceId(String s) { this.serviceId = s; return this; }
        public Builder instanceId(String s) { this.instanceId = s; return this; }
        public Builder host(String s) { this.host = s; return this; }
        public Builder port(int p) { this.port = p; return this; }
        public Builder healthy(boolean h) { this.healthy = h; return this; }
        public Builder weight(int w) { this.weight = w; return this; }
        public Builder tags(List<String> t) { this.tags = t; return this; }
        public Builder metadata(java.util.Map<String, String> m) { this.metadata = m; return this; }

        public ServiceInstance build() { return new ServiceInstance(this); }
    }
}
