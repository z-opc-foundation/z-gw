package com.zifang.z.gw.core.runtime;

import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.FilterChainBootstrap;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.gw.core.server.GatewayHandler;
import com.zifang.z.gw.core.server.GatewayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 网关启动门面 — 把配置 + 路由 + 各种 SPI 组合成一个可启动的 Server。
 *
 * <p>无 Spring 场景下使用:
 * <pre>{@code
 *   GatewayProperties props = YamlRouteLoader.load(new File("gateway.yaml"));
 *   GatewayBootstrap bootstrap = new GatewayBootstrap(props);
 *   bootstrap.start();
 *   bootstrap.awaitTermination();
 * }</pre>
 *
 * <p>Spring Boot 场景下由 starter 的 AutoConfiguration 装配。
 */
public class GatewayBootstrap {

    private static final Logger log = LoggerFactory.getLogger(GatewayBootstrap.class);

    private final GatewayProperties properties;
    private final InMemoryRouteRepository routeRepository = new InMemoryRouteRepository();
    private final RouteMatcher routeMatcher;
    private final FilterAssembler filterAssembler;
    private final BackendHttpClient backendClient;
    private final GatewayServer server;
    private final FilterChainBootstrap filterBootstrap;

    public GatewayBootstrap(GatewayProperties properties) {
        this.properties = properties;

        this.routeMatcher = new RouteMatcher();
        this.filterAssembler = new FilterAssembler();
        this.backendClient = new BackendHttpClient(properties.getServer());

        // 注册所有内置全局过滤器 + 过滤器工厂
        this.filterBootstrap = new FilterChainBootstrap(filterAssembler);

        GatewayHandler handler = new GatewayHandler(
                properties.getServer(), routeMatcher, filterAssembler, backendClient);
        this.server = new GatewayServer(properties.getServer(), handler);

        // 路由仓库变更自动刷新 RouteMatcher
        routeRepository.addChangeListener(event -> {
            log.info("Routes changed ({}), refreshing matcher", event);
            routeMatcher.refresh(routeRepository.getRouteDefinitions());
        });
    }

    public synchronized void start() throws Exception {
        log.info("=== Z-GW bootstrap starting ===");

        // 加载初始路由
        List<RouteDefinition> initial = properties.getRoutes();
        if (initial != null && !initial.isEmpty()) {
            routeRepository.replaceAll(initial);
        }
        routeMatcher.refresh(routeRepository.getRouteDefinitions());

        // 注册默认全局过滤器
        filterBootstrap.installDefaults();

        // 启动 Netty
        server.start();
        log.info("=== Z-GW bootstrap started ===");
    }

    public synchronized void stop() {
        log.info("Stopping Z-GW...");
        server.shutdown();
        backendClient.shutdown();
        log.info("Z-GW stopped");
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public GatewayProperties getProperties() {
        return properties;
    }

    public InMemoryRouteRepository getRouteRepository() {
        return routeRepository;
    }

    public RouteMatcher getRouteMatcher() {
        return routeMatcher;
    }

    public FilterAssembler getFilterAssembler() {
        return filterAssembler;
    }

    public ServerConfig getServerConfig() {
        return properties.getServer();
    }

    public BackendHttpClient getBackendClient() {
        return backendClient;
    }
}
