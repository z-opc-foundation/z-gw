package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.PredicateFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 谓词工厂注册表 — 全局单例,持有所有 {@link PredicateFactory} 实例。
 *
 * <p>注册方式:
 * <ul>
 *   <li>{@link #register(PredicateFactory)} — 程序化注册</li>
 *   <li>Spring Boot starter 通过 {@code @Bean} 注册并被 {@code ZGatewayAutoConfiguration} 注入</li>
 *   <li>Java SPI (META-INF/services/com.zifang.z.gw.api.PredicateFactory) — 自动发现</li>
 * </ul>
 */
public class PredicateFactoryRegistry {

    private static final PredicateFactoryRegistry INSTANCE = new PredicateFactoryRegistry();

    public static PredicateFactoryRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, PredicateFactory> factories = new ConcurrentHashMap<>();

    public PredicateFactoryRegistry() {
        // 注册内置工厂
        register(new PathPredicateFactory());
        register(new MethodPredicateFactory());
        register(new HeaderPredicateFactory());
        register(new HostPredicateFactory());
        register(new WeightPredicateFactory());
        register(new TimePredicateFactory());

        // 尝试 ServiceLoader 自动发现扩展点
        try {
            java.util.ServiceLoader<PredicateFactory> loader =
                    java.util.ServiceLoader.load(PredicateFactory.class);
            for (PredicateFactory f : loader) {
                register(f);
            }
        } catch (Throwable ignore) {
            // ServiceLoader 不可用时静默忽略
        }
    }

    public PredicateFactoryRegistry register(PredicateFactory factory) {
        if (factory == null) return this;
        factories.put(factory.name().toLowerCase(), factory);
        return this;
    }

    public PredicateFactory get(String name) {
        if (name == null) return null;
        return factories.get(name.toLowerCase());
    }

    public boolean contains(String name) {
        return name != null && factories.containsKey(name.toLowerCase());
    }

    public Collection<PredicateFactory> all() {
        return factories.values();
    }
}
