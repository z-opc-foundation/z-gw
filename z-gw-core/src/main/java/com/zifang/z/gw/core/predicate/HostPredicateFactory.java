package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.util.Map;

/**
 * Host 谓词 — 匹配请求 Host。
 *
 * <p>多值 OR,支持通配符 {@code *.example.com}(前缀匹配)。
 */
public class HostPredicateFactory implements PredicateFactory {

    public static final String NAME = "Host";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Host predicate requires at least one host pattern");
        }
        String host = ctx.getHost();
        if (host == null) {
            return PredicateResult.noMatch("Host predicate requires Host header");
        }
        // 取主机部分(去掉端口)
        int colonIdx = host.indexOf(':');
        if (colonIdx != -1) {
            host = host.substring(0, colonIdx);
        }
        for (String want : args.values()) {
            String pattern = want.trim();
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (host.endsWith(suffix)) {
                    return PredicateResult.match();
                }
            } else if (pattern.equalsIgnoreCase(host)) {
                return PredicateResult.match();
            }
        }
        return PredicateResult.noMatch("Host '" + host + "' not in " + args.values());
    }
}
