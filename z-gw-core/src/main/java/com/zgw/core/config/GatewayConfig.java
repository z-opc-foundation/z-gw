package com.zgw.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Gateway configuration
 */
public class GatewayConfig {

    @JsonProperty("server")
    private ServerConfig server = new ServerConfig();

    @JsonProperty("router")
    private RouterConfig router = new RouterConfig();

    public ServerConfig getServer() {
        return server;
    }

    public void setServer(ServerConfig server) {
        this.server = server;
    }

    public RouterConfig getRouter() {
        return router;
    }

    public void setRouter(RouterConfig router) {
        this.router = router;
    }

    @Override
    public String toString() {
        return "GatewayConfig{" +
                "server=" + server +
                ", router=" + router +
                '}';
    }

    /**
     * Server configuration
     */
    public static class ServerConfig {
        @JsonProperty("port")
        private int port = 8080;

        @JsonProperty("bossThreads")
        private int bossThreads = 1;

        @JsonProperty("workerThreads")
        private int workerThreads = 0; // 0 = use Netty default

        @JsonProperty("soBacklog")
        private int soBacklog = 1024;

        @JsonProperty("soKeepalive")
        private boolean soKeepalive = true;

        @JsonProperty("tcpNodelay")
        private boolean tcpNodelay = true;

        @JsonProperty("maxContentLength")
        private int maxContentLength = 10 * 1024 * 1024; // 10MB

        @JsonProperty("connectTimeout")
        private int connectTimeout = 3000; // 3 seconds

        @JsonProperty("readTimeout")
        private int readTimeout = 30000; // 30 seconds

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getBossThreads() {
            return bossThreads;
        }

        public void setBossThreads(int bossThreads) {
            this.bossThreads = bossThreads;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getSoBacklog() {
            return soBacklog;
        }

        public void setSoBacklog(int soBacklog) {
            this.soBacklog = soBacklog;
        }

        public boolean isSoKeepalive() {
            return soKeepalive;
        }

        public void setSoKeepalive(boolean soKeepalive) {
            this.soKeepalive = soKeepalive;
        }

        public boolean isTcpNodelay() {
            return tcpNodelay;
        }

        public void setTcpNodelay(boolean tcpNodelay) {
            this.tcpNodelay = tcpNodelay;
        }

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public int getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
        }

        @Override
        public String toString() {
            return "ServerConfig{" +
                    "port=" + port +
                    ", bossThreads=" + bossThreads +
                    ", workerThreads=" + workerThreads +
                    ", soBacklog=" + soBacklog +
                    ", soKeepalive=" + soKeepalive +
                    ", tcpNodelay=" + tcpNodelay +
                    ", maxContentLength=" + maxContentLength +
                    '}';
        }
    }

    /**
     * Router configuration
     */
    public static class RouterConfig {
        @JsonProperty("routes")
        private java.util.List<RouteDefinition> routes = new java.util.ArrayList<>();

        public java.util.List<RouteDefinition> getRoutes() {
            return routes;
        }

        public void setRoutes(java.util.List<RouteDefinition> routes) {
            this.routes = routes;
        }

        @Override
        public String toString() {
            return "RouterConfig{" +
                    "routes=" + routes +
                    '}';
        }
    }

    /**
     * Route definition
     */
    public static class RouteDefinition {
        @JsonProperty("id")
        private String id;

        @JsonProperty("path")
        private String path;

        @JsonProperty("method")
        private String method = "*";

        @JsonProperty("backend")
        private String backend;

        @JsonProperty("stripPrefix")
        private boolean stripPrefix = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public boolean isStripPrefix() {
            return stripPrefix;
        }

        public void setStripPrefix(boolean stripPrefix) {
            this.stripPrefix = stripPrefix;
        }

        @Override
        public String toString() {
            return "RouteDefinition{" +
                    "id='" + id + '\'' +
                    ", path='" + path + '\'' +
                    ", method='" + method + '\'' +
                    ", backend='" + backend + '\'' +
                    ", stripPrefix=" + stripPrefix +
                    '}';
        }
    }
}