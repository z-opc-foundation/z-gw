package com.zifang.z.gw.core.router;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.predicate.PredicateFactoryRegistry;
import com.zifang.z.gw.core.predicate.WeightPredicateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 路由注册表 + 匹配器 — 持有路由定义集合,对外提供 {@link #route(GatewayContext)} 命中查找。
 *
 * <p>设计要点:
 * <ul>
 *   <li>线程安全 — 路由变更走 {@link #refresh(List)} 全量替换,旧路由不可见后无引用会被 GC</li>
 *   <li>排序 — order 升序,同 order 按 id 字典序稳定</li>
 *   <li>灰度 — 同 group 的 weight 路由,通过权重 hash 选一</li>
 *   <li>禁用 — enabled=false 路由被忽略</li>
 * </ul>
 *
 * <p>与 SCG 的 {@code RoutePredicateHandlerMapping} 行为对齐:
 * 一旦命中路由即返回,不再继续查找后续路由。
 */
public class RouteMatcher {

    private static final Logger log = LoggerFactory.getLogger(RouteMatcher.class);

    private final AtomicReference<List<RouteDefinition>> sortedRoutes = new AtomicReference<>(Collections.emptyList());

    /** routeId -> Group → weight 累计值(灰度路由聚合) */
    private final java.util.Map<String, List<RouteDefinition>> groupedByWeight = new ConcurrentHashMap<>();

    private final PredicateFactoryRegistry predicateRegistry;

    public RouteMatcher() {
        this(PredicateFactoryRegistry.getInstance());
    }

    public RouteMatcher(PredicateFactoryRegistry predicateRegistry) {
        this.predicateRegistry = predicateRegistry;
    }

    /**
     * 全量刷新路由表 — 调用方负责线程安全(写时阻塞读)。
     */
    public synchronized void refresh(List<RouteDefinition> routes) {
        if (routes == null) {
            routes = Collections.emptyList();
        }
        // 过滤 disabled
        List<RouteDefinition> enabled = new ArrayList<>(routes.size());
        for (RouteDefinition r : routes) {
            if (r.isEnabled()) {
                enabled.add(r);
            }
        }
        // 排序:order 升序,id 字典序
        enabled.sort(Comparator.comparingInt(RouteDefinition::getOrder).thenComparing(RouteDefinition::getId));
        sortedRoutes.set(Collections.unmodifiableList(enabled));

        // 重建权重分组
        groupedByWeight.clear();
        for (RouteDefinition r : enabled) {
            for (PredicateDefinition p : r.getPredicates()) {
                if (WeightPredicateFactory.NAME.equalsIgnoreCase(p.getName())) {
                    String group = WeightPredicateFactory.extractGroup(p.getArgs());
                    groupedByWeight.computeIfAbsent(group, k -> new ArrayList<>()).add(r);
                    break;
                }
            }
        }

        log.info("RouteMatcher refreshed: {} enabled routes ({} in weight groups)",
                enabled.size(), groupedByWeight.size());
    }

    public List<RouteDefinition> currentRoutes() {
        return sortedRoutes.get();
    }

    /**
     * 根据请求查找匹配的路由。
     *
     * @return 匹配路由;无匹配返回 null
     */
    public RouteDefinition route(GatewayContext ctx) {
        List<RouteDefinition> all = sortedRoutes.get();
        // 第一遍:完全匹配非 weight 路由
        for (RouteDefinition route : all) {
            if (!hasWeightPredicate(route) && matchesAllPredicates(ctx, route)) {
                return route;
            }
        }
        // 第二遍:weight 路由按 group 聚合后,选一个
        // 用 Set 避免 group 内重复匹配
        java.util.Set<String> handledGroups = new java.util.HashSet<>();
        for (RouteDefinition route : all) {
            if (!hasWeightPredicate(route)) continue;
            if (matchesAllPredicates(ctx, route)) {
                String group = extractWeightGroup(route);
                if (handledGroups.contains(group)) continue;
                handledGroups.add(group);
                List<RouteDefinition> groupRoutes = groupedByWeight.getOrDefault(group, Collections.singletonList(route));
                return selectByWeight(ctx, groupRoutes);
            }
        }
        return null;
    }

    private boolean hasWeightPredicate(RouteDefinition route) {
        for (PredicateDefinition p : route.getPredicates()) {
            if (WeightPredicateFactory.NAME.equalsIgnoreCase(p.getName())) {
                return true;
            }
        }
        return false;
    }

    private String extractWeightGroup(RouteDefinition route) {
        for (PredicateDefinition p : route.getPredicates()) {
            if (WeightPredicateFactory.NAME.equalsIgnoreCase(p.getName())) {
                return WeightPredicateFactory.extractGroup(p.getArgs());
            }
        }
        return "_default";
    }

    private RouteDefinition selectByWeight(GatewayContext ctx, List<RouteDefinition> groupRoutes) {
        int total = 0;
        for (RouteDefinition r : groupRoutes) {
            total += extractWeight(r);
        }
        if (total <= 0) return groupRoutes.get(0);

        // 用 hash(ctx.requestId) 保证同一请求选同一分支(可选)
        // 这里用 ctx 的 hashCode 做简单随机
        long seed = ctx.getRequestId() != null ? ctx.getRequestId().hashCode() : System.nanoTime();
        java.util.Random rnd = new java.util.Random(seed);
        int hit = rnd.nextInt(total);
        int acc = 0;
        for (RouteDefinition r : groupRoutes) {
            acc += extractWeight(r);
            if (hit < acc) return r;
        }
        return groupRoutes.get(groupRoutes.size() - 1);
    }

    private int extractWeight(RouteDefinition route) {
        for (PredicateDefinition p : route.getPredicates()) {
            if (WeightPredicateFactory.NAME.equalsIgnoreCase(p.getName())) {
                return WeightPredicateFactory.extractWeight(p.getArgs());
            }
        }
        return 0;
    }

    /**
     * 检查路由的全部谓词是否都命中(AND)。
     */
    private boolean matchesAllPredicates(GatewayContext ctx, RouteDefinition route) {
        List<PredicateDefinition> predicates = route.getPredicates();
        if (predicates == null || predicates.isEmpty()) {
            return true;
        }
        for (PredicateDefinition pd : predicates) {
            PredicateFactory factory = predicateRegistry.get(pd.getName());
            if (factory == null) {
                log.warn("Unknown predicate factory: {}, route {} ignored", pd.getName(), route.getId());
                return false;
            }
            PredicateResult result = factory.apply(ctx, pd.getArgs());
            if (result.isNotMatched()) {
                if (log.isDebugEnabled()) {
                    log.debug("Route {} not match: {}", route.getId(), result.getReason());
                }
                return false;
            }
        }
        return true;
    }
}
