package com.zifang.z.gw.core.router;

import com.zifang.z.gw.api.FilterDefinition;
import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.filter.DefaultGatewayFilterChain;
import com.zifang.z.gw.core.filter.GatewayFilterFactoryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 过滤器组装器 — 把全局过滤器 + 路由级过滤器合并,按 order 排序,构造本次请求的 filter chain。
 *
 * <p>设计参考 SCG 的 {@code FilteringWebHandler}:
 * <ul>
 *   <li>全局过滤器通过 {@link #addGlobalFilter} 注册</li>
 *   <li>路由级过滤器从 {@link FilterDefinition} 通过工厂生成</li>
 *   <li>合并后按 order 升序排</li>
 * </ul>
 */
public class FilterAssembler {

    private static final Logger log = LoggerFactory.getLogger(FilterAssembler.class);

    private final List<GatewayFilter> globalFilters = new CopyOnWriteArrayList<>();
    private final GatewayFilterFactoryRegistry factoryRegistry;

    public FilterAssembler() {
        this(GatewayFilterFactoryRegistry.getInstance());
    }

    public FilterAssembler(GatewayFilterFactoryRegistry factoryRegistry) {
        this.factoryRegistry = factoryRegistry;
    }

    public FilterAssembler addGlobalFilter(GatewayFilter filter) {
        if (filter != null) {
            globalFilters.add(filter);
            // 重排序
            globalFilters.sort(Comparator.comparingInt(GatewayFilter::order));
        }
        return this;
    }

    /**
     * 组装针对本次请求的过滤器链(全局 + 路由级)。
     */
    public GatewayFilterChain assemble(GatewayContext ctx, RouteDefinition route) {
        List<GatewayFilter> all = new ArrayList<>(globalFilters.size() + 8);
        all.addAll(globalFilters);

        // 路由级过滤器
        if (route != null && route.getFilters() != null) {
            for (FilterDefinition fd : route.getFilters()) {
                GatewayFilter filter = buildFilter(fd);
                if (filter != null) {
                    all.add(filter);
                }
            }
        }
        // 按 order 排序
        all.sort(Comparator.comparingInt(GatewayFilter::order));
        if (log.isTraceEnabled()) {
            log.trace("Assembled {} filters for route {}", all.size(), route == null ? "(none)" : route.getId());
        }
        return new DefaultGatewayFilterChain(Collections.unmodifiableList(all));
    }

    private GatewayFilter buildFilter(FilterDefinition fd) {
        try {
            GatewayFilterFactoryRegistry reg = factoryRegistry;
            // 如果工厂表里没有,尝试按需加载该工厂类
            if (!reg.contains(fd.getName())) {
                tryLoadFactory(fd.getName());
            }
            com.zifang.z.gw.api.GatewayFilterFactory factory = reg.get(fd.getName());
            if (factory == null) {
                log.warn("Unknown filter factory: {}, skip", fd.getName());
                return null;
            }
            return factory.apply(fd.getArgs());
        } catch (Exception e) {
            log.error("Build filter {} failed", fd.getName(), e);
            return null;
        }
    }

    /**
     * 反射加载自定义过滤器工厂 — yml 可写 {@code class=...} 形式动态加载。
     */
    private void tryLoadFactory(String name) {
        // 简化:仅支持已注册的 name;如需 SPI 扩展自行实现
    }

    public List<GatewayFilter> getGlobalFilters() {
        return Collections.unmodifiableList(globalFilters);
    }
}
