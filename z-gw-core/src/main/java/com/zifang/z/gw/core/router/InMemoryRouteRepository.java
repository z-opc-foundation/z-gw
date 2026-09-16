package com.zifang.z.gw.core.router;

import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.api.RouteLocator;
import com.zifang.z.gw.api.RouteWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版路由定位器 + 写入器 — 默认实现,单进程内 CRUD 路由。
 *
 * <p>cluster 模块可基于此扩展 Redis / Nacos 写入器 + 分布式同步广播。
 */
public class InMemoryRouteRepository implements RouteLocator, RouteWriter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRouteRepository.class);

    private final Map<String, RouteDefinition> routes = new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public List<RouteDefinition> getRouteDefinitions() {
        return Collections.unmodifiableList(new ArrayList<>(routes.values()));
    }

    @Override
    public void save(RouteDefinition route) {
        if (route == null || route.getId() == null) return;
        boolean added = !routes.containsKey(route.getId());
        routes.put(route.getId(), route);
        log.info("Route {}: {}", added ? "added" : "updated", route.getId());
        notify(added ? ChangeListener.ChangeEvent.ADDED : ChangeListener.ChangeEvent.UPDATED);
    }

    @Override
    public void delete(String id) {
        if (id == null) return;
        RouteDefinition removed = routes.remove(id);
        if (removed != null) {
            log.info("Route removed: {}", id);
            notify(ChangeListener.ChangeEvent.REMOVED);
        }
    }

    @Override
    public void replaceAll(List<RouteDefinition> newRoutes) {
        routes.clear();
        if (newRoutes != null) {
            for (RouteDefinition r : newRoutes) {
                if (r != null && r.getId() != null) {
                    routes.put(r.getId(), r);
                }
            }
        }
        log.info("Routes replaced: total={}", routes.size());
        notify(ChangeListener.ChangeEvent.REFRESH);
    }

    public RouteDefinition get(String id) {
        return id == null ? null : routes.get(id);
    }

    public int size() {
        return routes.size();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    private void notify(ChangeListener.ChangeEvent event) {
        for (ChangeListener l : listeners) {
            try {
                l.onChange(event);
            } catch (Exception e) {
                log.warn("Route listener failed", e);
            }
        }
    }
}
