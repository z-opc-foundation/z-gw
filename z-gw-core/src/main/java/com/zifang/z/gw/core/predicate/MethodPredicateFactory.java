package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.util.Map;

/**
 * HTTP 方法谓词 — 匹配 {@code GET} / {@code POST} / {@code PUT} / {@code DELETE} 等。
 *
 * <p>多值 OR 关系: yml {@code Method=GET,POST} 表示 GET 或 POST 都命中。
 */
public class MethodPredicateFactory implements PredicateFactory {

    public static final String NAME = "Method";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Method predicate requires at least one method");
        }
        String method = ctx.getMethod();
        if (method == null) {
            return PredicateResult.noMatch("Method predicate requires a method");
        }
        for (String raw : args.values()) {
            if (raw == null) continue;
            // 支持 "GET,POST" 多值 + 单值
            for (String want : raw.split(",")) {
                if (method.equalsIgnoreCase(want.trim())) {
                    return PredicateResult.match();
                }
            }
        }
        return PredicateResult.noMatch("Method '" + method + "' not in " + args.values());
    }
}
