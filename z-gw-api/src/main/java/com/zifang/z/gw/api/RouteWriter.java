package com.zifang.z.gw.api;

/**
 * 路由写入器 SPI — admin 模块通过它动态新增/更新/删除路由,
 * 默认由 core 模块的内存版实现,admin 可扩展 Redis/Nacos 写入器。
 */
public interface RouteWriter {

    /** 保存一条路由(新增或覆盖) */
    void save(RouteDefinition route);

    /** 删除路由 */
    void delete(String id);

    /** 全量替换(用于整体 reload) */
    void replaceAll(java.util.List<RouteDefinition> routes);
}
