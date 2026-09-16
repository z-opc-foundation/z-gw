package com.zifang.z.gw.examples.config;

import com.zifang.z.gw.core.predicate.PredicateFactoryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * z-gw-examples 补丁配置 — 注册 {@link PredicateFactoryRegistry} bean。
 *
 * <p>z-gw-spring-boot-starter 1.0.0-SNAPSHOT 漏掉了 {@code PredicateFactoryRegistry} 的
 * {@code @Bean} 声明，导致 {@code MetaController} 启动失败。
 *
 * <p>TODO: 上游修复后移除本类。
 */
@Configuration
public class ZGatewayExamplesPatchConfig {

    @Bean
    public PredicateFactoryRegistry predicateFactoryRegistry() {
        return new PredicateFactoryRegistry();
    }
}