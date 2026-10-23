package com.zifang.z.gw.starter;

import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.config.ServerConfig;
import org.springframework.beans.factory.InitializingBean;
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
public class ZGatewayProperties extends GatewayProperties implements InitializingBean {

    /** 路由刷新间隔(ms),0 表示只在配置变更时刷新 */
    private long refreshIntervalMs = 0;

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * Spring 绑定完成后立刻自检 — 缺关键配置必须在<b>启动期</b>炸，不能拖到第一次请求。
     *
     * <p>绑定发生在 {@code postProcessBeforeInitialization}，而本回调在
     * {@code initializeBean} 的最后一步 ⇒ 拿到的已经是绑定后的值。</p>
     *
     * <p>当前唯一被强制要求的项是 {@code zgw.security.jwt-secret}：它<b>刻意没有默认值</b>
     * （本仓曾内置过一个"请在生产环境修改"的占位串，已随 1.0.4/1.0.5/1.0.6 流出且不可撤回），
     * 现在缺省就启动失败。判定口径见 {@link GatewayProperties#validate()}。</p>
     *
     * @throws IllegalStateException 安全开关开着但没给 jwtSecret
     */
    @Override
    public void afterPropertiesSet() {
        validate();
    }
}
