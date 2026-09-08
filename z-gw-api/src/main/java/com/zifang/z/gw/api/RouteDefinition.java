package com.zifang.z.gw.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路由定义 — 一条路由描述"什么样的请求 → 转发到哪个目标 + 应用哪些过滤器"。
 *
 * <p>设计融合:
 * <ul>
 *   <li>Spring Cloud Gateway 的 {@code RouteDefinition} 字段集 (id/uri/predicates/filters)</li>
 *   <li>Apache ShenYu 的 {@code SelectorRule} 思路 (id + 优先级 + 启用)</li>
 *   <li>APISIX 的 {@code Route} 扩展点 (metadata/labels/timeout)</li>
 * </ul>
 *
 * <p>不可变对象,通过 {@link Builder} 构造。
 */
public final class RouteDefinition {

    /** 路由唯一 ID,作为运行时 Map Key */
    private final String id;

    /** 目标 URI — 支持 http(s)://...、lb://serviceName、forward://localPath */
    private final String uri;

    /** 排序权重:数字越小优先级越高;相同 order 时按 ID 字典序稳定排序 */
    private final int order;

    /** 是否启用,false 的路由在路由表里被忽略 */
    private final boolean enabled;

    /** 谓词列表 (AND 关系) — 全部命中才匹配 */
    private final List<PredicateDefinition> predicates;

    /** 路由级过滤器 — 仅对该路由生效 */
    private final List<FilterDefinition> filters;

    /** 元数据 — 灰度比例、超时、缓存 key 等扩展字段 */
    private final Map<String, String> metadata;

    private RouteDefinition(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.uri = Objects.requireNonNull(b.uri, "uri");
        this.order = b.order;
        this.enabled = b.enabled;
        this.predicates = b.predicates == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(b.predicates);
        this.filters = b.filters == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(b.filters);
        this.metadata = b.metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
    }

    public String getId() { return id; }
    public String getUri() { return uri; }
    public int getOrder() { return order; }
    public boolean isEnabled() { return enabled; }
    public List<PredicateDefinition> getPredicates() { return predicates; }
    public List<FilterDefinition> getFilters() { return filters; }
    public Map<String, String> getMetadata() { return metadata; }

    public String metadata(String key) {
        return metadata == null ? null : metadata.get(key);
    }

    public String metadata(String key, String defaultValue) {
        if (metadata == null) return defaultValue;
        String v = metadata.get(key);
        return v == null ? defaultValue : v;
    }

    /** 简易 {@link #equals} — 仅基于 id,路由表按 id 去重 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RouteDefinition)) return false;
        return id.equals(((RouteDefinition) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RouteDefinition{" +
                "id='" + id + '\'' +
                ", uri='" + uri + '\'' +
                ", order=" + order +
                ", enabled=" + enabled +
                ", predicates=" + predicates +
                ", filters=" + filters +
                '}';
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String uri;
        private int order = 0;
        private boolean enabled = true;
        private List<PredicateDefinition> predicates;
        private List<FilterDefinition> filters;
        private Map<String, String> metadata;

        public Builder id(String id) { this.id = id; return this; }
        public Builder uri(String uri) { this.uri = uri; return this; }
        public Builder order(int order) { this.order = order; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder predicates(List<PredicateDefinition> predicates) { this.predicates = predicates; return this; }
        public Builder filters(List<FilterDefinition> filters) { this.filters = filters; return this; }
        public Builder metadata(Map<String, String> metadata) { this.metadata = metadata; return this; }

        public RouteDefinition build() {
            return new RouteDefinition(this);
        }
    }
}
