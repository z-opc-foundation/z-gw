package com.zifang.z.gw.starter.host;

import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * z-gw 路由配置 (代码构建).
 * <p>
 * 背景: {@link RouteDefinition} 是 final 类, 无 setter, Spring Boot relaxed binding 无法从
 * properties / yaml 直接构造 (Property=... were left unbound 错误).
 * <p>
 * 这里在代码里用 {@link RouteDefinition.Builder} 构造, 注册到 z-gw 自己的
 * {@link InMemoryRouteRepository} bean — 然后 ZGatewayAutoConfiguration.onContextRefreshedEvent
 * 已经会从 props.getRoutes() 拉取. 我们用 @Bean override ZGatewayProperties.getRoutes() 不可行
 * (route 已经在 ZGatewayProperties bean 构造时绑定), 所以用直接注入 InMemoryRouteRepository 强制
 * 覆盖, 时机选在 Spring 启动的最后阶段 (SmartInitializingSingleton).
 *
 * <p>实际更可靠的做法: 在 SmartInitializingSingleton 里调用 routeRepository.replaceAll().
 */
@Configuration
@ConditionalOnProperty(prefix = "z.gw", name = "enabled", havingValue = "true")
public class ZGwRoutesConfig {

    private static final Logger log = LoggerFactory.getLogger(ZGwRoutesConfig.class);

    /**
     * 构建 z-gw 默认路由: 把 /api/**, /doc.html, /v3/openapi 代理到 Tomcat 8888.
     * 实际注入由 ZGwRoutesInitializer 完成.
     */
    public static List<RouteDefinition> buildDefaultRoutes() {
        RouteDefinition apiRoute = RouteDefinition.builder()
                .id("opc-api")
                .uri("http://localhost:8888")
                .order(0)
                .enabled(true)
                .predicates(Collections.singletonList(
                        PredicateDefinition.of("Path", "/api/**")))
                .filters(Collections.emptyList())
                .metadata(Collections.emptyMap())
                .build();

        RouteDefinition docRoute = RouteDefinition.builder()
                .id("opc-doc")
                .uri("http://localhost:8888")
                .order(0)
                .enabled(true)
                .predicates(Collections.singletonList(
                        PredicateDefinition.of("Path", "/doc.html")))
                .filters(Collections.emptyList())
                .metadata(Collections.emptyMap())
                .build();

        RouteDefinition openapiRoute = RouteDefinition.builder()
                .id("opc-openapi")
                .uri("http://localhost:8888")
                .order(0)
                .enabled(true)
                .predicates(Collections.singletonList(
                        PredicateDefinition.of("Path", "/v3/openapi")))
                .filters(Collections.emptyList())
                .metadata(Collections.emptyMap())
                .build();

        return Arrays.asList(apiRoute, docRoute, openapiRoute);
    }

    /**
     * 在所有 singleton bean 初始化之后 (z-gw 的 GatewayServerLifecycle.onContextRefreshedEvent 之后)
     * 把构建好的 routes 强制塞进 InMemoryRouteRepository.
     * <p>
     * GatewayServerLifecycle 内部用 routeRepository.replaceAll(props.getRoutes()) —
     * 我们的 routes 是代码构建的, props.getRoutes() 是空 List, 这里直接 replaceAll(code-routes).
     */
    @Bean
    public SmartInitializingSingleton zGwRoutesInitializer(InMemoryRouteRepository routeRepository) {
        return () -> {
            List<RouteDefinition> routes = buildDefaultRoutes();
            routeRepository.replaceAll(routes);
            log.info("[z-gw] routes injected via SmartInitializingSingleton: {} routes registered", routes.size());
            routes.forEach(r -> log.info("  - {} -> {} ({})", r.getId(), r.getUri(), r.getPredicates()));
        };
    }
}