package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Map;

/**
 * AddRequestHeader 过滤器工厂 — 往出站请求加上额外 header。
 *
 * <p>yml 例: {@code filters: [AddRequestHeader=X-Trace-Id, z-gw]}。
 */
public class AddRequestHeaderFilterFactory implements GatewayFilterFactory {

    public static final String NAME = "AddRequestHeader";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("AddRequestHeader requires name,value");
        }
        Map.Entry<String, String> e = args.entrySet().iterator().next();
        // 第一个 key 是 name,value 是 value;若 key 是 _genkey_0 则 value 是 "name,value"
        String headerName;
        String headerValue;
        if ("_genkey_0".equals(e.getKey())) {
            String[] parts = e.getValue().split(",", 2);
            headerName = parts[0].trim();
            headerValue = parts.length > 1 ? parts[1].trim() : "";
        } else {
            headerName = e.getKey();
            headerValue = e.getValue();
        }
        return new AddRequestHeaderFilter(headerName, headerValue);
    }

    private static class AddRequestHeaderFilter implements GatewayFilter {

        private final String name;
        private final String value;

        AddRequestHeaderFilter(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 300; }

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            @SuppressWarnings("unchecked")
            Map<String, String> hdrs = (Map<String, String>) ctx.getAttribute("req.headers");
            if (hdrs != null) {
                hdrs.put(name, value);
            }
            ctx.setAttribute("req.header." + name.toLowerCase(), value);
            chain.filter(ctx);
        }
    }
}
