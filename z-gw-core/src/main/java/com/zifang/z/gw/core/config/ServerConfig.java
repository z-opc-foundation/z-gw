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

    /**
     * 可信代理跳数：<b>0 = 不信任任何代理</b>，{@code clientIp} 只取 TCP 远端地址，
     * 完全不看 {@code X-Forwarded-For} / {@code X-Real-IP}。
     *
     * <p>网关前面真的挂了 N 层自有代理时才设成 N。设成 N 时，{@code clientIp} 取
     * {@code X-Forwarded-For} <b>从右往左数第 N 段</b>：每一跳代理都把自己看到的来源
     * append 到链尾，所以越靠右越接近网关、越可信；从左往右数最左边那段是整条链路上
     * 最没有约束力的一段，任何能连到网关的客户端自己填一个头就能改掉它。</p>
     *
     * <p><b>N 怎么算</b>（配错的人基本都错在这里）：按 nginx 那个
     * {@code proxy_add_x_forwarded_for} 的默认行为，<b>XFF 的段数 = 代理层数</b>，
     * 不是层数 +1 —— 最外层那层代理是 TCP remote，它只提供"连接来源"，
     * 不会把自己写进 XFF。设 C=8.8.8.8 → P1=10.0.0.1 → P2=10.0.0.2 → 网关：</p>
     * <pre>
     * P1 转发时发：XFF: 8.8.8.8
     * P2 转发时发：XFF: 8.8.8.8, 10.0.0.1        ← P2 自己不在里面
     * 网关侧 remote = 10.0.0.2，trustedProxyHops = 2，取 parts[len - 2] = 8.8.8.8
     * </pre>
     * <p>客户端在 P1 之前伪造的段只会加在<b>最左边</b>，从右数第 N 段碰不到它 ——
     * 前提是"客户端伪造的段数刚好被可信层数吃掉"。真的有人直连网关（绕过代理）并自己填 XFF，
     * 这个计数会失真，所以下面那条前提不是可选项。</p>
     *
     * <p><b>这个值大于 0 声明的是"只有内网代理能连到我"，前提由网络层保证。</b>
     * 端口对公网开放还开着它，等于没设。代码里没法替你兜住这一点。</p>
     */
    private int trustedProxyHops = 0;

    /** CORS */
    private boolean corsEnabled = false;
    private String corsAllowedOrigins = "*";
    private String corsAllowedMethods = "GET,POST,PUT,DELETE,OPTIONS,PATCH";
    private String corsAllowedHeaders = "*";
    private long corsMaxAge = 3600;

    public int getTrustedProxyHops() { return trustedProxyHops; }
    public void setTrustedProxyHops(int trustedProxyHops) { this.trustedProxyHops = trustedProxyHops; }

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
