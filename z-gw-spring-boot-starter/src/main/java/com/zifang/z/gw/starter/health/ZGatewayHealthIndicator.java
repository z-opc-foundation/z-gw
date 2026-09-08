package com.zifang.z.gw.starter.health;

import com.zifang.z.gw.core.server.GatewayServer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * z-gw 健康检查端点 — 暴露给 Spring Boot Actuator。
 *
 * <p>访问 {@code /actuator/health/zGateway} 查看网关状态。
 */
public class ZGatewayHealthIndicator implements HealthIndicator {

    private final GatewayServer server;

    public ZGatewayHealthIndicator(GatewayServer server) {
        this.server = server;
    }

    @Override
    public Health health() {
        if (server == null || !server.isStarted()) {
            return Health.down().withDetail("reason", "gateway server not started").build();
        }
        return Health.up()
                .withDetail("started", true)
                .build();
    }
}
