package com.zifang.z.gw.core.filter;

import com.zifang.z.gw.api.GlobalFilter;
import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.filter.global.JwtAuthGlobalFilter;
import com.zifang.z.gw.core.filter.global.LoggingGlobalFilter;
import com.zifang.z.gw.core.filter.global.MetricsGlobalFilter;
import com.zifang.z.gw.core.filter.global.TracingGlobalFilter;
import com.zifang.z.gw.core.http.BackendHttpClient;
import com.zifang.z.gw.core.service.LbUriResolver;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.filter.factory.AddRequestHeaderFilterFactory;
import com.zifang.z.gw.core.filter.factory.AddResponseHeaderFilterFactory;
import com.zifang.z.gw.core.filter.factory.HystrixFilterFactory;
import com.zifang.z.gw.core.filter.factory.PrefixPathFilterFactory;
import com.zifang.z.gw.core.filter.factory.RateLimitFilterFactory;
import com.zifang.z.gw.core.filter.factory.RetryFilterFactory;
import com.zifang.z.gw.core.filter.factory.RewritePathFilterFactory;
import com.zifang.z.gw.core.filter.factory.StripPrefixFilterFactory;
import com.zifang.z.gw.core.filter.proxy.NettyProxyFilter;
import com.zifang.z.gw.core.filter.global.ErrorHandlingGlobalFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 过滤器链启动器 — 注册内置全局过滤器 + 内置过滤器工厂。
 *
 * <p>调用方在 {@link com.zifang.z.gw.core.runtime.GatewayBootstrap#start()} 中调用 {@link #installDefaults()}
 * 完成内置 SPI 的全量注册。
 */
public class FilterChainBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FilterChainBootstrap.class);

    private final FilterAssembler filterAssembler;
    private final GatewayFilterFactoryRegistry factoryRegistry;
    /** 传给 NettyProxyFilter，让后端等待上限跟随配置而不是硬编码。 */
    private final com.zifang.z.gw.core.config.ServerConfig serverConfig;
    /**
     * 安全配置（nullable）：由 GatewayBootstrap / starter 自动配置传入。
     * null = 旧构造器口径（不注册 JwtAuthGlobalFilter）；非 null 且 enabled=true 时才注册。
     */
    private final GatewayProperties.SecurityConfig security;

    public FilterChainBootstrap(FilterAssembler filterAssembler) {
        this(filterAssembler, GatewayFilterFactoryRegistry.getInstance(),
                new com.zifang.z.gw.core.config.ServerConfig(), null);
    }

    public FilterChainBootstrap(FilterAssembler filterAssembler, GatewayFilterFactoryRegistry factoryRegistry) {
        this(filterAssembler, factoryRegistry, new com.zifang.z.gw.core.config.ServerConfig(), null);
    }

    public FilterChainBootstrap(FilterAssembler filterAssembler,
                                com.zifang.z.gw.core.config.ServerConfig serverConfig) {
        this(filterAssembler, GatewayFilterFactoryRegistry.getInstance(), serverConfig, null);
    }

    public FilterChainBootstrap(FilterAssembler filterAssembler,
                                GatewayFilterFactoryRegistry factoryRegistry,
                                com.zifang.z.gw.core.config.ServerConfig serverConfig) {
        this(filterAssembler, factoryRegistry, serverConfig, null);
    }

    /** 带安全配置：供 GatewayBootstrap / starter 自动配置把 {@code zgw.security.*} 接进过滤链。 */
    public FilterChainBootstrap(FilterAssembler filterAssembler,
                                com.zifang.z.gw.core.config.ServerConfig serverConfig,
                                GatewayProperties.SecurityConfig security) {
        this(filterAssembler, GatewayFilterFactoryRegistry.getInstance(), serverConfig, security);
    }

    public FilterChainBootstrap(FilterAssembler filterAssembler,
                                GatewayFilterFactoryRegistry factoryRegistry,
                                com.zifang.z.gw.core.config.ServerConfig serverConfig,
                                GatewayProperties.SecurityConfig security) {
        this.filterAssembler = filterAssembler;
        this.factoryRegistry = factoryRegistry;
        this.serverConfig = serverConfig == null
                ? new com.zifang.z.gw.core.config.ServerConfig() : serverConfig;
        this.security = security;
    }

    public void installDefaults() {
        // === 内置全局过滤器 ===
        // 顺序:Tracing -> Metrics -> Logging -> Error -> Proxy
        //
        // 这里<b>没有</b> CORS 过滤器。CORS 由 GatewayHandler 在链外处理：预检在
        // channelRead0 里被 writeCorsOptions 拦下返回（**仅当 corsEnabled=true**，
        // 关掉时 OPTIONS 走正常路由匹配），实际响应的 ACAO 由
        // writeJson / writeFullResponse / writeError 各自按 CorsPolicy 补。
        // 此前链里挂过一个 CorsGlobalFilter，它只设了一个全仓零读取的 attribute
        // (cors.short.circuit)、不设任何响应头、也不短路，是个可证明的 no-op，
        // 而 Javadoc 却写着「自动添加 CORS 响应头」—— 只会误导读代码的人。
        filterAssembler.addGlobalFilter(new TracingGlobalFilter());
        filterAssembler.addGlobalFilter(new MetricsGlobalFilter());
        filterAssembler.addGlobalFilter(new LoggingGlobalFilter());
        // JWT 鉴权（order=0，Tracing 之后、ErrorHandling/Proxy 之前）：仅当显式传入安全配置
        // 且 enabled=true 时注册。enabled=false 或旧构造器（未传 security）都不注册，
        // 与 GatewayProperties#validate() 「显式关闭才放行」的口径一致。
        if (security != null && security.isEnabled()) {
            filterAssembler.addGlobalFilter(new JwtAuthGlobalFilter(security));
        }
        filterAssembler.addGlobalFilter(new ErrorHandlingGlobalFilter());

        // === 代理过滤器: order=999 最晚执行 ===
        filterAssembler.addGlobalFilter(new NettyProxyFilter(
                new BackendHttpClient(serverConfig), LbUriResolver.defaults(), serverConfig));

        // === 内置过滤器工厂 ===
        factoryRegistry.register(new StripPrefixFilterFactory());
        factoryRegistry.register(new PrefixPathFilterFactory());
        factoryRegistry.register(new RewritePathFilterFactory());
        factoryRegistry.register(new AddRequestHeaderFilterFactory());
        factoryRegistry.register(new AddResponseHeaderFilterFactory());
        factoryRegistry.register(new RateLimitFilterFactory());
        factoryRegistry.register(new HystrixFilterFactory());
        factoryRegistry.register(new RetryFilterFactory());

        log.info("Default filters and factories installed");
    }

    public FilterChainBootstrap addGlobal(GlobalFilter filter) {
        filterAssembler.addGlobalFilter(filter);
        return this;
    }

    public FilterChainBootstrap addFactory(com.zifang.z.gw.api.GatewayFilterFactory factory) {
        factoryRegistry.register(factory);
        return this;
    }
}
