package com.zifang.z.gw.core.filter;

import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关过滤器工厂注册表 — yml 配置 {@code filters: [StripPrefix=2]} 时通过此表查找工厂。
 */
public class GatewayFilterFactoryRegistry {

    private static final GatewayFilterFactoryRegistry INSTANCE = new GatewayFilterFactoryRegistry();

    public static GatewayFilterFactoryRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, GatewayFilterFactory> factories = new ConcurrentHashMap<>();

    public GatewayFilterFactoryRegistry() {
        // 注册所有内置过滤器工厂(见 core.filter.factory 包)
        try {
            java.util.ServiceLoader<GatewayFilterFactory> loader =
                    java.util.ServiceLoader.load(GatewayFilterFactory.class);
            for (GatewayFilterFactory f : loader) {
                register(f);
            }
        } catch (Throwable ignore) {
            // ignore
        }
    }

    public GatewayFilterFactoryRegistry register(GatewayFilterFactory factory) {
        if (factory == null) return this;
        factories.put(factory.name().toLowerCase(), factory);
        return this;
    }

    public GatewayFilterFactory get(String name) {
        if (name == null) return null;
        return factories.get(name.toLowerCase());
    }

    public boolean contains(String name) {
        return name != null && factories.containsKey(name.toLowerCase());
    }

    public Collection<GatewayFilterFactory> all() {
        return factories.values();
    }
}
