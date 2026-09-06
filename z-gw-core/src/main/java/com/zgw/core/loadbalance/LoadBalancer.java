package com.zgw.core.loadbalance;

import java.util.List;

/**
 * Load balancer interface
 */
public interface LoadBalancer {

    /**
     * Select a backend instance from the list
     *
     * @param instances list of available instances
     * @param context   load balance context
     * @return selected instance, or null if no instance available
     */
    Instance select(List<Instance> instances, LoadBalanceContext context);

    /**
     * Get load balancer name
     */
    String getName();

    /**
     * Instance representation
     */
    class Instance {
        private final String id;
        private final String host;
        private final int port;
        private final int weight;
        private volatile boolean healthy;
        private volatile long lastAccessTime;
        private volatile int activeConnections;

        public Instance(String id, String host, int port) {
            this(id, host, port, 100);
        }

        public Instance(String id, String host, int port, int weight) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.weight = weight;
            this.healthy = true;
            this.lastAccessTime = System.currentTimeMillis();
            this.activeConnections = 0;
        }

        public String getAddress() {
            return host + ":" + port;
        }

        public String getUrl() {
            return "http://" + getAddress();
        }

        // Getters and setters
        public String getId() {
            return id;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getWeight() {
            return weight;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        public long getLastAccessTime() {
            return lastAccessTime;
        }

        public void setLastAccessTime(long lastAccessTime) {
            this.lastAccessTime = lastAccessTime;
        }

        public int getActiveConnections() {
            return activeConnections;
        }

        public void incrementActiveConnections() {
            this.activeConnections++;
        }

        public void decrementActiveConnections() {
            this.activeConnections--;
        }

        @Override
        public String toString() {
            return "Instance{" +
                    "id='" + id + '\'' +
                    ", address='" + getAddress() + '\'' +
                    ", weight=" + weight +
                    ", healthy=" + healthy +
                    ", activeConnections=" + activeConnections +
                    '}';
        }
    }

    /**
     * Load balance context
     */
    class LoadBalanceContext {
        private String clientIp;
        private String requestUri;
        private String serviceName;

        public static LoadBalanceContext builder() {
            return new LoadBalanceContext();
        }

        public LoadBalanceContext clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public LoadBalanceContext requestUri(String requestUri) {
            this.requestUri = requestUri;
            return this;
        }

        public LoadBalanceContext serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        // Getters
        public String getClientIp() {
            return clientIp;
        }

        public String getRequestUri() {
            return requestUri;
        }

        public String getServiceName() {
            return serviceName;
        }
    }
}