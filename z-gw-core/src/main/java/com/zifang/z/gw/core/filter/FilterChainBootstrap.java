package com.zifang.z.gw.core.filter;

import com.zifang.z.gw.api.GlobalFilter;
import com.zifang.z.gw.core.filter.global.CorsGlobalFilter;
import com.zifang.z.gw.core.filter.global.LoggingGlobalFilter;
import com.zifang.z.gw.core.filter.global.MetricsGlobalFilter;
import com.zifang.z.gw.core.filter.global.TracingGlobalFilter;
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

    public FilterChainBootstrap(FilterAssembler filterAssembler) {
        this(filterAssembler, GatewayFilterFactoryRegistry.getInstance());
    }

    public FilterChainBootstrap(FilterAssembler filterAssembler, GatewayFilterFactoryRegistry factoryRegistry) {
        this.filterAssembler = filterAssembler;
        this.factoryRegistry = factoryRegistry;
    }

    public void installDefaults() {
        // === 内置全局过滤器 ===
        // 顺序:Tracing -> Metrics -> CORS -> Logging -> Error -> Proxy
        filterAssembler.addGlobalFilter(new TracingGlobalFilter());
        filterAssembler.addGlobalFilter(new MetricsGlobalFilter());
        filterAssembler.addGlobalFilter(new CorsGlobalFilter());
        filterAssembler.addGlobalFilter(new LoggingGlobalFilter());
        filterAssembler.addGlobalFilter(new ErrorHandlingGlobalFilter());

        // === 代理过滤器: order=999 最晚执行 ===
        filterAssembler.addGlobalFilter(new NettyProxyFilter());

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
