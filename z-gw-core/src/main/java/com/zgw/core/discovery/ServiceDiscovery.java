package com.zgw.core.discovery;

import com.zgw.core.loadbalance.LoadBalancer;

import java.util.List;
import com.zifang.util.core.lang.RandomUtil;

/**
 * Service discovery interface
 */
public interface ServiceDiscovery {

    /**
     * Register a service instance
     */
    void register(String serviceName, ServiceInstance instance);

    /**
     * Deregister a service instance
     */
    void deregister(String serviceName, String instanceId);

    /**
     * Get instances for a service
     */
    List<ServiceInstance> getInstances(String serviceName);

    /**
     * Watch for service changes
     */
    void watch(String serviceName, ServiceChangeListener listener);

    /**
     * Start the service discovery
     */
    void start();

    /**
     * Stop the service discovery
     */
    void stop();

    /**
     * Service change listener
     */
    @FunctionalInterface
    interface ServiceChangeListener {
        void onChange(String serviceName, java.util.List<ServiceInstance> instances);
    }

    /**
     * Service instance
     */
    class ServiceInstance {
        private final String id;
        private final String serviceName;
        private final String host;
        private final int port;
        private final int weight;
        private final boolean healthy;
        private final long registrationTime;
        private final java.util.Map<String, String> metadata;

        private ServiceInstance(Builder builder) {
            this.id = builder.id;
            this.serviceName = builder.serviceName;
            this.host = builder.host;
            this.port = builder.port;
            this.weight = builder.weight;
            this.healthy = builder.healthy;
            this.registrationTime = builder.registrationTime;
            this.metadata = new java.util.HashMap<>(builder.metadata);
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getAddress() {
            return host + ":" + port;
        }

        public String getUrl() {
            return "http://" + getAddress();
        }

        // Getters
        public String getId() {
            return id;
        }

        public String getServiceName() {
            return serviceName;
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

        public long getRegistrationTime() {
            return registrationTime;
        }

        public java.util.Map<String, String> getMetadata() {
            return metadata;
        }

        public LoadBalancer.Instance toLoadBalancerInstance() {
            return new LoadBalancer.Instance(id, host, port, weight);
        }

        @Override
        public String toString() {
            return "ServiceInstance{" +
                    "id='" + id + '\'' +
                    ", serviceName='" + serviceName + '\'' +
                    ", address='" + getAddress() + '\'' +
                    ", weight=" + weight +
                    ", healthy=" + healthy +
                    '}';
        }

        public static class Builder {
            private String id = com.zifang.util.core.lang.RandomUtil.uuid();
            private String serviceName;
            private String host;
            private int port = 8080;
            private int weight = 100;
            private boolean healthy = true;
            private long registrationTime = System.currentTimeMillis();
            private java.util.Map<String, String> metadata = new java.util.HashMap<>();

            public Builder id(String id) {
                this.id = id;
                return this;
            }

            public Builder serviceName(String serviceName) {
                this.serviceName = serviceName;
                return this;
            }

            public Builder host(String host) {
                this.host = host;
                return this;
            }

            public Builder port(int port) {
                this.port = port;
                return this;
            }

            public Builder weight(int weight) {
                this.weight = weight;
                return this;
            }

            public Builder healthy(boolean healthy) {
                this.healthy = healthy;
                return this;
            }

            public Builder metadata(java.util.Map<String, String> metadata) {
                this.metadata = new java.util.HashMap<>(metadata);
                return this;
            }

            public Builder addMetadata(String key, String value) {
                this.metadata.put(key, value);
                return this;
            }

            public ServiceInstance build() {
                if (serviceName == null || serviceName.isEmpty()) {
                    throw new IllegalArgumentException("serviceName is required");
                }
                if (host == null || host.isEmpty()) {
                    throw new IllegalArgumentException("host is required");
                }
                return new ServiceInstance(this);
            }
        }
    }
}