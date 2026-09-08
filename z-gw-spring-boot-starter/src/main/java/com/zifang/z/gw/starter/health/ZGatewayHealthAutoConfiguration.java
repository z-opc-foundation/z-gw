package com.zifang.z.gw.starter.health;

import com.zifang.z.gw.core.server.GatewayServer;
import com.zifang.z.gw.starter.ZGatewayProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * z-gw 健康检查自动装配 — 当 actuator + z-gw 同时存在时生效。
 */
@Configuration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "zgw", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ZGatewayHealthAutoConfiguration {

    @Bean
    public HealthIndicator zGatewayHealth(GatewayServer server) {
        return new ZGatewayHealthIndicator(server);
    }
}
