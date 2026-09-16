package com.zifang.z.gw.core.runtime;

import com.zifang.z.gw.api.FilterDefinition;
import com.zifang.z.gw.api.PredicateDefinition;
import com.zifang.z.gw.api.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简路由加载器 — 直接 Java 代码构造,不依赖 SnakeYAML 等第三方库。
 *
 * <p>生产使用 {@code application.yml} 路由配置由 spring-boot-starter 通过
 * {@code GatewayProperties} + {@code @ConfigurationProperties} 解析。
 *
 * <p>本类提供两种用法:
 * <ol>
 *   <li>{@link #defaultRoutes()} — 内置几条演示路由</li>
 *   <li>{@link #fromMapList(List)} — 接收 yml-like Map 数据(由 starter 转换)</li>
 * </ol>
 */
public class YamlRouteLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlRouteLoader.class);

    /** 加载文件并解析为 RouteDefinition 列表(简化版,需要外部已经 JSON/YAML 解析) */
    public static List<RouteDefinition> loadFromFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] bytes = is.readAllBytes();
            // 此处仅占位 — 实际实现中应当用 SnakeYAML 或 Jackson YAML
            // 我们支持纯 JSON 格式,生产推荐 starter 的 @ConfigurationProperties 方式
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            log.warn("loadFromFile: using simple JSON parser, recommended to use Spring Boot starter");
            return SimpleJsonRouteParser.parse(text);
        }
    }

    /** 内置演示路由 */
    public static List<RouteDefinition> defaultRoutes() {
        List<RouteDefinition> routes = new ArrayList<>();

        // 示例 1: 简单静态路由
        routes.add(RouteDefinition.builder()
                .id("demo-echo")
                .uri("http://httpbin.org")
                .order(0)
                .predicates(Collections.singletonList(PredicateDefinition.of("Path", "/demo/**")))
                .filters(Arrays.asList(
                        FilterDefinition.of("StripPrefix", "1"),
                        FilterDefinition.of("AddResponseHeader", "X-Gateway, z-gw-demo")
                ))
                .build());

        // 示例 2: 灰度路由(权重 90/10)
        routes.add(RouteDefinition.builder()
                .id("user-service-v1")
                .uri("http://user-service-v1:8080")
                .order(1)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", "/api/users/**"),
                        PredicateDefinition.of("Weight", "user_group,90")
                ))
                .build());
        routes.add(RouteDefinition.builder()
                .id("user-service-v2-canary")
                .uri("http://user-service-v2:8080")
                .order(2)
                .predicates(Arrays.asList(
                        PredicateDefinition.of("Path", "/api/users/**"),
                        PredicateDefinition.of("Weight", "user_group,10")
                ))
                .build());

        // 示例 3: 限流路由
        routes.add(RouteDefinition.builder()
                .id("api-limited")
                .uri("lb://backend-service")
                .order(3)
                .predicates(Collections.singletonList(PredicateDefinition.of("Path", "/api/limited/**")))
                .filters(Collections.singletonList(
                        FilterDefinition.of("RequestRateLimiter", "replenishRate=10,burstCapacity=20")
                ))
                .build());

        return routes;
    }

    /**
     * 从 Spring Boot 配置形式(List&lt;Map&lt;String,Object&gt;&gt;)构造 RouteDefinition。
     * starter 用此方法把 yml 配置转成运行时对象。
     */
    @SuppressWarnings("unchecked")
    public static List<RouteDefinition> fromMapList(List<Map<String, Object>> mapList) {
        List<RouteDefinition> result = new ArrayList<>();
        if (mapList == null) return result;
        for (Map<String, Object> map : mapList) {
            result.add(fromMap(map));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static RouteDefinition fromMap(Map<String, Object> map) {
        RouteDefinition.Builder b = RouteDefinition.builder()
                .id((String) map.get("id"))
                .uri((String) map.get("uri"))
                .order(intValue(map.get("order"), 0))
                .enabled(boolValue(map.get("enabled"), true));

        Object predicates = map.get("predicates");
        if (predicates instanceof List) {
            b.predicates(parsePredicates((List<Object>) predicates));
        }
        Object filters = map.get("filters");
        if (filters instanceof List) {
            b.filters(parseFilters((List<Object>) filters));
        }
        Object metadata = map.get("metadata");
        if (metadata instanceof Map) {
            Map<String, String> md = new LinkedHashMap<>();
            ((Map<Object, Object>) metadata).forEach((k, v) -> md.put(String.valueOf(k), String.valueOf(v)));
            b.metadata(md);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static List<PredicateDefinition> parsePredicates(List<Object> list) {
        List<PredicateDefinition> result = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                String name = (String) m.get("name");
                Object argsObj = m.get("args");
                Map<String, String> args = new LinkedHashMap<>();
                if (argsObj instanceof Map) {
                    ((Map<Object, Object>) argsObj).forEach((k, v) -> args.put(String.valueOf(k), String.valueOf(v)));
                }
                result.add(PredicateDefinition.of(name, args));
            } else if (o instanceof String) {
                // 简化语法: "Path=/api/**"
                String s = (String) o;
                int eq = s.indexOf('=');
                if (eq > 0) {
                    result.add(PredicateDefinition.of(s.substring(0, eq).trim(), s.substring(eq + 1).trim()));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<FilterDefinition> parseFilters(List<Object> list) {
        List<FilterDefinition> result = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                String name = (String) m.get("name");
                Object argsObj = m.get("args");
                Map<String, String> args = new LinkedHashMap<>();
                if (argsObj instanceof Map) {
                    ((Map<Object, Object>) argsObj).forEach((k, v) -> args.put(String.valueOf(k), String.valueOf(v)));
                }
                result.add(FilterDefinition.of(name, args));
            } else if (o instanceof String) {
                String s = (String) o;
                int eq = s.indexOf('=');
                if (eq > 0) {
                    result.add(FilterDefinition.of(s.substring(0, eq).trim(), s.substring(eq + 1).trim()));
                }
            }
        }
        return result;
    }

    private static int intValue(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolValue(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
