package com.zifang.z.gw.api;

/**
 * 路由定位器 SPI — 路由定义可以来自多种来源:
 * <ul>
 *   <li>{@code StaticRouteLocator} — yml/application.properties</li>
 *   <li>{@code InMemoryRouteLocator} — admin API 直接 push 到内存</li>
 *   <li>{@code NacosRouteLocator} — 监听 Nacos DataId 变更</li>
 *   <li>{@code RedisRouteLocator} — 监听 Redis Pub/Sub 或 keyspace</li>
 *   <li>{@code KubernetesRouteLocator} — 监听 K8s Gateway API CRD</li>
 * </ul>
 *
 * <p>所有实现通过 {@link #getRouteDefinitions()} 提供路由快照;
 * 动态源还应支持 {@link java.util.function.Consumer} 注册变更回调。
 */
public interface RouteLocator {

    /**
     * 拉取当前所有路由定义 — 同步快照,调用方按需定期拉取或缓存。
     */
    java.util.List<RouteDefinition> getRouteDefinitions();

    /**
     * 路由变更监听器。
     */
    interface ChangeListener {
        /**
         * 当路由定义集合发生变化时调用。
         *
         * @param event 事件类型
         */
        void onChange(ChangeEvent event);

        enum ChangeEvent {
            /** 整体刷新(加载配置) */
            REFRESH,
            /** 单条新增 */
            ADDED,
            /** 单条更新 */
            UPDATED,
            /** 单条删除 */
            REMOVED
        }
    }
}
