package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Map;

/**
 * PrefixPath 过滤器工厂 — 给请求 path 加上前缀。
 *
 * <p>yml 例: {@code filters: [PrefixPath=/api]} 把 {@code /v1/users} 变成 {@code /api/v1/users}。
 */
public class PrefixPathFilterFactory implements GatewayFilterFactory {

    public static final String NAME = "PrefixPath";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        String prefix = args == null || args.isEmpty() ? "" : args.values().iterator().next();
        return new PrefixPathFilter(prefix);
    }

    private static class PrefixPathFilter implements GatewayFilter {

        private final String prefix;

        PrefixPathFilter(String prefix) {
            this.prefix = prefix == null ? "" : prefix.trim();
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 100; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            String path = ctx.getPath();
            if (path != null && !prefix.isEmpty()) {
                String newPath = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (!path.startsWith("/")) {
                    newPath = newPath + "/" + path;
                } else {
                    newPath = newPath + path;
                }
                ctx.setPath(newPath);
            }
            chain.filter(ctx);
        }
    }
}
