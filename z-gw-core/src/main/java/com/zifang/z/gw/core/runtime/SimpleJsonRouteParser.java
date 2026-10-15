package com.zifang.z.gw.core.runtime;

import com.zifang.z.gw.api.FilterDefinition;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.util.json.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 JSON 路由解析器 — 不依赖 SnakeYAML,通过 z-util JsonUtil 解析 JSON 数组形式。
 *
 * <p>Spring Boot starter 场景下推荐用 {@code @ConfigurationProperties} 直接绑定
 * {@link com.zifang.z.gw.core.config.GatewayProperties} 而非此解析器。
 *
 * <p>支持 JSON 形式:
 * <pre>{@code
 * [
 *   {
 *     "id": "demo",
 *     "uri": "http://httpbin.org",
 *     "predicates": [{"name": "Path", "args": {"_genkey_0": "/demo/**"}}],
 *     "filters": [{"name": "StripPrefix", "args": {"_genkey_0": "1"}}]
 *   }
 * ]
 * }</pre>
 */
public final class SimpleJsonRouteParser {

    private SimpleJsonRouteParser() {}

    @SuppressWarnings("unchecked")
    public static List<RouteDefinition> parse(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            // JsonUtil.parse 的产物是 JsonObject/JsonArray, 必须先摊平成 plain 结构,
            // 否则下面的 instanceof Map/List 判断不成立
            Object root = JsonUtil.toPlainValue(JsonUtil.parse(json));
            if (root instanceof List) {
                return YamlRouteLoader.fromMapList((List<Map<String, Object>>) root);
            }
            if (root instanceof Map) {
                Map<String, Object> obj = (Map<String, Object>) root;
                Object routes = obj.get("routes");
                if (routes instanceof List) {
                    return YamlRouteLoader.fromMapList((List<Map<String, Object>>) routes);
                }
            }
            return Collections.emptyList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse routes JSON: " + e.getMessage(), e);
        }
    }
}
