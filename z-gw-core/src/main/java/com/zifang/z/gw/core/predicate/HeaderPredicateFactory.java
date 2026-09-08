package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.util.Map;

/**
 * Header 谓词 — 请求头存在且值匹配给定正则表达式。
 *
 * <p>例: yml {@code Header=X-Trace-Id, .+} 表示"必须有非空 X-Trace-Id header"。
 */
public class HeaderPredicateFactory implements PredicateFactory {

    public static final String NAME = "Header";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        // args: 第一个 key 是 header 名,value 是 regex
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Header predicate requires headerName=regex");
        }
        Map.Entry<String, String> first = args.entrySet().iterator().next();
        String headerName = first.getKey();
        String expected = first.getValue();
        String actual = ctx.getAttribute("req.header." + headerName, String.class);
        if (actual == null) actual = (String) ctx.getAttribute("req.header." + headerName.toLowerCase());
        if (actual == null) {
            return PredicateResult.noMatch("Missing header: " + headerName);
        }
        if (expected == null || expected.isEmpty() || ".".equals(expected)) {
            return PredicateResult.match();
        }
        if (actual.matches(expected)) {
            return PredicateResult.match();
        }
        return PredicateResult.noMatch("Header " + headerName + "='" + actual + "' not match " + expected);
    }
}
