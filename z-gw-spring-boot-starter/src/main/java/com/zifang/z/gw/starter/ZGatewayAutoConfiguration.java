package com.zifang.z.gw.starter;

import com.zifang.z.gw.core.filter.FilterChainBootstrap;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.gw.core.runtime.GatewayBootstrap;
import com.zifang.z.gw.core.server.GatewayHandler;
import com.zifang.z.gw.core.server.GatewayServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * z-gw Spring Boot 自动装配入口。
 *
 * <p>在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 中注册后,Spring Boot 启动时自动加载:
 * <ol>
 *   <li>{@link ZGatewayProperties} — 绑定 {@code zgw.*} 配置</li>
 *   <li>{@link InMemoryRouteRepository} — 路由 CRUD 仓库</li>
 *   <li>{@link RouteMatcher} — 路由匹配</li>
 *   <li>{@link FilterAssembler} + {@link FilterChainBootstrap} — 过滤器链</li>
 *   <li>{@link BackendHttpClient} — 出站 HTTP 客户端</li>
 *   <li>{@link GatewayServer} — Netty 服务器</li>
 * </ol>
 *
 * <p>启动生命周期: {@link GatewayServerLifecycle} 在 {@link ContextRefreshedEvent} 时启动,
 * 在 {@code @PreDestroy} 时关闭。
 */
@AutoConfiguration
@ConditionalOnClass(GatewayServer.class)
@ConditionalOnProperty(prefix = "zgw", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ZGatewayProperties.class)
public class ZGatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZGatewayAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(InMemoryRouteRepository.class)
    public InMemoryRouteRepository routeRepository() {
        return new InMemoryRouteRepository();
    }

    @Bean
    @ConditionalOnMissingBean(RouteMatcher.class)
    public RouteMatcher routeMatcher() {
        return new RouteMatcher();
    }

    @Bean
    @ConditionalOnMissingBean(FilterAssembler.class)
    public FilterAssembler filterAssembler() {
        return new FilterAssembler();
    }

    @Bean
    @ConditionalOnMissingBean(FilterChainBootstrap.class)
    public FilterChainBootstrap filterChainBootstrap(FilterAssembler filterAssembler) {
        return new FilterChainBootstrap(filterAssembler);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(BackendHttpClient.class)
    public BackendHttpClient backendHttpClient(ZGatewayProperties props) {
        return new BackendHttpClient(props.getServer());
    }

    @Bean
    @ConditionalOnMissingBean(GatewayHandler.class)
    public GatewayHandler gatewayHandler(ZGatewayProperties props,
                                         RouteMatcher routeMatcher,
                                         FilterAssembler filterAssembler,
                                         BackendHttpClient backendClient) {
        return new GatewayHandler(props.getServer(), routeMatcher, filterAssembler, backendClient);
    }

    @Bean
    @ConditionalOnMissingBean(GatewayServer.class)
    public GatewayServer gatewayServer(ZGatewayProperties props, GatewayHandler handler) {
        return new GatewayServer(props.getServer(), handler);
    }

    /**
     * 启动生命周期管理 — Spring 上下文 refresh 完毕后启动 Netty,容器关闭时优雅停机。
     */
    @Bean
    public GatewayServerLifecycle gatewayServerLifecycle(GatewayServer server,
                                                          ZGatewayProperties props,
                                                          InMemoryRouteRepository routeRepository,
                                                          RouteMatcher routeMatcher,
                                                          FilterChainBootstrap filterBootstrap) {
        return new GatewayServerLifecycle(server, props, routeRepository, routeMatcher, filterBootstrap);
    }

    /**
     * 路由刷新监听器 — 当 admin API 修改了 {@link InMemoryRouteRepository},
     * 自动刷新 {@link RouteMatcher}。
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> routeAutoRefresher(InMemoryRouteRepository routeRepository,
                                                                          RouteMatcher routeMatcher) {
        return event -> {
            routeRepository.addChangeListener(evt -> routeMatcher.refresh(routeRepository.getRouteDefinitions()));
            routeMatcher.refresh(routeRepository.getRouteDefinitions());
        };
    }

    /**
     * 启动器 — 包装核心的 {@link GatewayBootstrap} 让外部可以拿到所有组件。
     */
    @Bean
    @ConditionalOnMissingBean(GatewayBootstrap.class)
    public GatewayBootstrap gatewayBootstrap(ZGatewayProperties props,
                                             InMemoryRouteRepository routeRepository,
                                             RouteMatcher routeMatcher,
                                             FilterAssembler filterAssembler,
                                             FilterChainBootstrap filterBootstrap,
                                             BackendHttpClient backendClient,
                                             GatewayServer server) {
        GatewayBootstrap bootstrap = new GatewayBootstrap(props) {
            @Override
            public InMemoryRouteRepository getRouteRepository() { return routeRepository; }
            @Override
            public RouteMatcher getRouteMatcher() { return routeMatcher; }
            @Override
            public FilterAssembler getFilterAssembler() { return filterAssembler; }
            @Override
            public BackendHttpClient getBackendClient() { return backendClient; }
        };
        // 暴露 server bootstrap access
        try {
            java.lang.reflect.Field f = GatewayBootstrap.class.getDeclaredField("server");
            f.setAccessible(true);
            f.set(bootstrap, server);
        } catch (Exception ignore) {}
        return bootstrap;
    }

    /**
     * Gateway Server 启动生命周期 — Spring refresh 完毕启动 Netty,PreDestroy 时关闭。
     */
    public static class GatewayServerLifecycle implements org.springframework.beans.factory.DisposableBean {

        private final GatewayServer server;
        private final ZGatewayProperties props;
        private final InMemoryRouteRepository routeRepository;
        private final RouteMatcher routeMatcher;
        private final FilterChainBootstrap filterBootstrap;
        private volatile boolean started = false;

        public GatewayServerLifecycle(GatewayServer server,
                                       ZGatewayProperties props,
                                       InMemoryRouteRepository routeRepository,
                                       RouteMatcher routeMatcher,
                                       FilterChainBootstrap filterBootstrap) {
            this.server = server;
            this.props = props;
            this.routeRepository = routeRepository;
            this.routeMatcher = routeMatcher;
            this.filterBootstrap = filterBootstrap;
        }

        @EventListener(ContextRefreshedEvent.class)
        public void onContextRefreshed() {
            if (started) return;
            log.info("Z-GW starter: Context refreshed, starting gateway...");
            try {
                // 加载初始路由
                if (props.getRoutes() != null && !props.getRoutes().isEmpty()) {
                    routeRepository.replaceAll(props.getRoutes());
                }
                routeMatcher.refresh(routeRepository.getRouteDefinitions());

                // 安装默认过滤器链
                filterBootstrap.installDefaults();

                // 启动 Netty
                server.start();
                started = true;
                log.info("Z-GW started successfully on port {}", props.getServer().getPort());
            } catch (Exception e) {
                log.error("Failed to start Z-GW", e);
                throw new RuntimeException("Z-GW startup failed", e);
            }
        }

        @Override
        public void destroy() {
            log.info("Z-GW starter: destroying, shutting down gateway...");
            try {
                server.shutdown();
            } catch (Exception e) {
                log.warn("Error during shutdown", e);
            }
        }
    }
}
