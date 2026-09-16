package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RewritePath 过滤器工厂 — 通过正则把请求 path 重写。
 *
 * <p>yml 例: {@code RewritePath=/red(?<segment>.*), /$\{segment\}} 把 {@code /red/blue} 变成 {@code /blue}。
 */
public class RewritePathFilterFactory implements GatewayFilterFactory {

    public static final String NAME = "RewritePath";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        if (args == null || args.size() < 2) {
            throw new IllegalArgumentException("RewritePath requires regex,replacement");
        }
        java.util.List<String> values = new java.util.ArrayList<>(args.values());
        String regex = values.get(0);
        String replacement = values.get(1);
        Pattern p = Pattern.compile(regex);
        return new RewritePathFilter(p, replacement);
    }

    private static class RewritePathFilter implements GatewayFilter {

        private final Pattern pattern;
        private final String replacement;

        RewritePathFilter(Pattern pattern, String replacement) {
            this.pattern = pattern;
            this.replacement = replacement;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 200; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            String path = ctx.getPath();
            if (path != null) {
                Matcher m = pattern.matcher(path);
                if (m.matches()) {
                    String newPath = m.replaceFirst(replacement);
                    ctx.setPath(newPath);
                }
            }
            chain.filter(ctx);
        }
    }
}
