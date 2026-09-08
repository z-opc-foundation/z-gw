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

    /** 默认安全配置 — JWT/Public Paths 等 */
    public static class SecurityConfig {
        private boolean enabled = true;
        private String jwtSecret = "z-gw-default-secret-please-change-in-production";
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
