package com.zifang.z.gw.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 过滤器定义 — 描述一个过滤器工厂 + 配置参数,例如 {@code StripPrefix=2} 或
 * {@code AddRequestHeader=X-Trace-Id,${traceId}}。
 *
 * <p>过滤器分两层:
 * <ul>
 *   <li><b>路由级过滤器</b> (filter definition on route) — 仅对单条路由生效,按 route.filters 顺序执行</li>
 *   <li><b>全局过滤器</b> (GlobalFilter) — 对所有路由生效,按 {@link #order} 排序</li>
 * </ul>
 *
 * <p>本类仅用于"路由级"的 yml 描述;全局过滤器用 {@link GlobalFilter} 接口直接定义。
 */
public final class FilterDefinition {

    /** 过滤器工厂名,例如 {@code StripPrefix} / {@code AddRequestHeader} / {@code RewritePath} / {@code CircuitBreaker} */
    private final String name;

    /** 工厂参数 — 不同过滤器需要的参数集不同 */
    private final Map<String, String> args;

    private FilterDefinition(String name, Map<String, String> args) {
        this.name = Objects.requireNonNull(name, "filter name");
        this.args = args == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public String getName() { return name; }
    public Map<String, String> getArgs() { return args; }

    public static FilterDefinition of(String name, String singleArg) {
        return new FilterDefinition(name, Collections.singletonMap("_genkey_0", singleArg));
    }

    public static FilterDefinition of(String name, Map<String, String> args) {
        return new FilterDefinition(name, args);
    }

    @Override
    public String toString() {
        return "FilterDefinition{" + name + ", args=" + args + '}';
    }
}
