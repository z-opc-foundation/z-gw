package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Map;

/**
 * StripPrefix 过滤器工厂 — 把请求 path 的前 N 段路径去掉。
 *
 * <p>yml 例: {@code filters: [StripPrefix=2]} 把 {@code /api/user/list} 变成 {@code /list}。
 */
public class StripPrefixFilterFactory implements GatewayFilterFactory {

    public static final String NAME = "StripPrefix";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        int parts = parseParts(args);
        return new StripPrefixFilter(parts);
    }

    private static int parseParts(Map<String, String> args) {
        if (args == null || args.isEmpty()) return 1;
        String v = args.values().iterator().next();
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static class StripPrefixFilter implements GatewayFilter {

        private final int parts;

        StripPrefixFilter(int parts) {
            this.parts = parts;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 100; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            String path = ctx.getPath();
            if (path != null && parts > 0) {
                for (int i = 0; i < parts; i++) {
                    int slash = path.indexOf('/', 1);
                    if (slash > 0) {
                        path = path.substring(slash);
                    } else {
                        path = "/";
                        break;
                    }
                }
                ctx.setPath(path);
            }
            chain.filter(ctx);
        }
    }
}
