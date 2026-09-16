package com.zifang.z.gw.core.config;

/**
 * 网关服务器配置 — 不可变 POJO,通过 {@link GatewayProperties} 绑定。
 *
 * <p>包含:Netty 服务器参数、HTTP 出站参数、连接池、超时等。
 */
public class ServerConfig {

    private int port = 9090;
    private String host = "0.0.0.0";
    private int bossThreads = 1;
    private int workerThreads = 0;          // 0 = Netty 默认 (CPU 核数 * 2)
    private int soBacklog = 1024;
    private boolean tcpNodelay = true;
    private boolean soKeepalive = true;
    private int maxContentLength = 10 * 1024 * 1024; // 10MB

    /** 出站连接池 */
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 30000;
    private int writeTimeoutMs = 30000;
    private int maxPoolSize = 500;

    /** 业务线程池(阻塞操作放这里,避免占用 Netty EventLoop) */
    private int businessThreadCore = 16;
    private int businessThreadMax = 64;
    private int businessQueue = 1000;

    /** WebSocket 支持 */
    private boolean websocketEnabled = true;

    /** CORS */
    private boolean corsEnabled = false;
    private String corsAllowedOrigins = "*";
    private String corsAllowedMethods = "GET,POST,PUT,DELETE,OPTIONS,PATCH";
    private String corsAllowedHeaders = "*";
    private long corsMaxAge = 3600;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getBossThreads() { return bossThreads; }
    public void setBossThreads(int bossThreads) { this.bossThreads = bossThreads; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getSoBacklog() { return soBacklog; }
    public void setSoBacklog(int soBacklog) { this.soBacklog = soBacklog; }
    public boolean isTcpNodelay() { return tcpNodelay; }
    public void setTcpNodelay(boolean tcpNodelay) { this.tcpNodelay = tcpNodelay; }
    public boolean isSoKeepalive() { return soKeepalive; }
    public void setSoKeepalive(boolean soKeepalive) { this.soKeepalive = soKeepalive; }
    public int getMaxContentLength() { return maxContentLength; }
    public void setMaxContentLength(int maxContentLength) { this.maxContentLength = maxContentLength; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getWriteTimeoutMs() { return writeTimeoutMs; }
    public void setWriteTimeoutMs(int writeTimeoutMs) { this.writeTimeoutMs = writeTimeoutMs; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    public int getBusinessThreadCore() { return businessThreadCore; }
    public void setBusinessThreadCore(int businessThreadCore) { this.businessThreadCore = businessThreadCore; }
    public int getBusinessThreadMax() { return businessThreadMax; }
    public void setBusinessThreadMax(int businessThreadMax) { this.businessThreadMax = businessThreadMax; }
    public int getBusinessQueue() { return businessQueue; }
    public void setBusinessQueue(int businessQueue) { this.businessQueue = businessQueue; }
    public boolean isWebsocketEnabled() { return websocketEnabled; }
    public void setWebsocketEnabled(boolean websocketEnabled) { this.websocketEnabled = websocketEnabled; }
    public boolean isCorsEnabled() { return corsEnabled; }
    public void setCorsEnabled(boolean corsEnabled) { this.corsEnabled = corsEnabled; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    public String getCorsAllowedMethods() { return corsAllowedMethods; }
    public void setCorsAllowedMethods(String corsAllowedMethods) { this.corsAllowedMethods = corsAllowedMethods; }
    public String getCorsAllowedHeaders() { return corsAllowedHeaders; }
    public void setCorsAllowedHeaders(String corsAllowedHeaders) { this.corsAllowedHeaders = corsAllowedHeaders; }
    public long getCorsMaxAge() { return corsMaxAge; }
    public void setCorsMaxAge(long corsMaxAge) { this.corsMaxAge = corsMaxAge; }
}
