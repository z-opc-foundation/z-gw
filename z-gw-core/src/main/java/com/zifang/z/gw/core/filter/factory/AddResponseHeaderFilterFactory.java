package com.zifang.z.gw.core.filter.factory;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.GatewayFilterFactory;

import java.util.Map;

/**
 * AddResponseHeader 过滤器工厂 — 往客户端响应加上额外 header。
 *
 * <p>yml 例: {@code filters: [AddResponseHeader=X-Gateway, z-gw-v2]}。
 *
 * <p>注:出站响应写回在 NettyProxyFilter 中完成,本过滤器把 header 写入 ctx attribute,
 * 由 GatewayHandler.writeFullResponse 时合并。
 */
public class AddResponseHeaderFilterFactory implements GatewayFilterFactory {

    public static final String NAME = "AddResponseHeader";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("AddResponseHeader requires name,value");
        }
        Map.Entry<String, String> e = args.entrySet().iterator().next();
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
        return new AddResponseHeaderFilter(headerName, headerValue);
    }

    private static class AddResponseHeaderFilter implements GatewayFilter {

        private final String name;
        private final String value;

        AddResponseHeaderFilter(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String name() { return NAME; }

        @Override
        public int order() { return 800; }  // 接近出站

        @Override
        public void filter(GatewayContext ctx, GatewayFilterChain chain) {
            @SuppressWarnings("unchecked")
            Map<String, String> hdrs = (Map<String, String>) ctx.getAttribute("resp.headers");
            if (hdrs == null) {
                hdrs = new java.util.HashMap<>();
                ctx.setAttribute("resp.headers", hdrs);
            }
            hdrs.put(name, value);
            chain.filter(ctx);
        }
    }
}
