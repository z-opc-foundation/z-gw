package com.zifang.z.gw.core.config;

import com.zifang.z.gw.api.RouteDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 网关根配置 — 包含服务器、路由列表、安全/限流/熔断默认配置、集群配置。
 *
 * <p>由 spring-boot-starter 通过 {@code @ConfigurationProperties(prefix = "zgw")} 绑定 yml。
 * 也可通过程序化 API 直接构造(无 Spring 场景)。
 */
public class GatewayProperties {

    private boolean enabled = true;
    private ServerConfig server = new ServerConfig();
    private SecurityConfig security = new SecurityConfig();
    private List<RouteDefinition> routes = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }
    public SecurityConfig getSecurity() { return security; }
    public void setSecurity(SecurityConfig security) { this.security = security; }
    public List<RouteDefinition> getRoutes() { return routes; }
    public void setRoutes(List<RouteDefinition> routes) {
        this.routes = routes == null ? Collections.emptyList() : routes;
    }

    /**
     * 启动前自检 — 缺关键配置必须在启动时炸，不能拖到第一次请求。
     *
     * <p>{@code jwtSecret} <b>没有默认值</b>：曾经内置过一个"请在生产环境修改"的占位串，
     * 而占位串一旦随中央发布件流出去，就会变成"看起来能用"的默认密钥 ——
     * 后来的人拿它当可用密钥直接上线，风险比"没有密钥"还高（没人会想到要改它）。
     * 所以现在只有两种合法状态：<b>显式提供真值</b>，或者<b>把安全开关关掉</b>
     * （{@code zgw.security.enabled=false}）—— 后者是明说"我不用 JWT"，不是"忘了配"。</p>
     *
     * @throws IllegalStateException 安全开关开着但没给 jwtSecret
     */
    public void validate() {
        SecurityConfig s = this.security;
        if (s == null || !s.isEnabled()) {
            return;
        }
        String secret = s.getJwtSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "zgw.security.jwt-secret 未配置：网关安全配置没有默认值，"
                            + "必须由外部显式提供（环境变量 / 配置中心 / K8s secretKeyRef）；"
                            + "若确实不使用 JWT，请显式设置 zgw.security.enabled=false");
        }
    }

    /** 默认安全配置 — JWT/Public Paths 等 */
    public static class SecurityConfig {
        private boolean enabled = true;

        /**
         * JWT 密钥 — <b>刻意没有默认值</b>，必须由外部显式提供。
         *
         * <p>本仓曾内置一个"请在生产环境修改"的占位串，它已随 1.0.4/1.0.5/1.0.6 三个中央版本
         * 流出且不可撤回；留着默认值等于邀请后来的人把它当"可用默认密钥"直接用。
         * 现在的口径是：{@code zgw.security.enabled=true} 就必须有密钥（{@link #validate()} 启动即失败），
         * 不用 JWT 就显式 {@code zgw.security.enabled=false}。</p>
         */
        private String jwtSecret;

        private List<String> publicPaths = new ArrayList<>();
        private long jwtLeewaySeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public List<String> getPublicPaths() { return publicPaths; }
        public void setPublicPaths(List<String> publicPaths) { this.publicPaths = publicPaths; }
        public long getJwtLeewaySeconds() { return jwtLeewaySeconds; }
        public void setJwtLeewaySeconds(long jwtLeewaySeconds) { this.jwtLeewaySeconds = jwtLeewaySeconds; }
    }
}
