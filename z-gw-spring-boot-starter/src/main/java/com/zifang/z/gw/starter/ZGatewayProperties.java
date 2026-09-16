package com.zifang.z.gw.starter;

import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.config.ServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot starter 配置属性 — 通过 {@code zgw.*} 前缀绑定 application.yml。
 *
 * <p>例:
 * <pre>
 * zgw:
 *   enabled: true
 *   server:
 *     port: 9090
 *     workerThreads: 16
 *   security:
 *     enabled: true
 *     jwtSecret: my-secret
 *     publicPaths:
 *       - /health
 *       - /api/auth/**
 *   routes:
 *     - id: demo
 *       uri: http://httpbin.org
 *       predicates:
 *         - name: Path
 *           args: {_genkey_0: /demo/**}
 *       filters:
 *         - name: StripPrefix
 *           args: {_genkey_0: "1"}
 * </pre>
 */
@ConfigurationProperties(prefix = "zgw")
public class ZGatewayProperties extends GatewayProperties {

    /** 路由刷新间隔(ms),0 表示只在配置变更时刷新 */
    private long refreshIntervalMs = 0;

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }
}
