package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.util.Map;

/**
 * 路径谓词 — 支持 {@code /api/**} / {@code /api/*} / 精确匹配 / {@code /api/users/{id}} (占位符)。
 *
 * <p>设计参考 APISIX 的 radixtree 路径匹配:
 * <ul>
 *   <li>{@code *}  匹配单段路径</li>
 *   <li>{@code **} 匹配零或多段路径</li>
 *   <li>{@code {name}} 占位符(目前仅当占位声明存在时使用,仅作语法兼容)</li>
 * </ul>
 */
public class PathPredicateFactory implements PredicateFactory {

    public static final String NAME = "Path";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Path predicate requires at least one pattern");
        }
        // 多模式 OR 关系(逗号分隔)
        for (String pattern : args.values()) {
            if (matches(ctx.getPath(), pattern)) {
                return PredicateResult.match();
            }
        }
        return PredicateResult.noMatch("Path '" + ctx.getPath() + "' not match any pattern");
    }

    public static boolean matches(String path, String pattern) {
        if (path == null) return false;
        if (pattern == null) return false;
        // 精确
        if (pattern.equals(path)) return true;
        // 全通配
        if ("/**".equals(pattern) || "/**/*".equals(pattern)) return true;

        // 转成正则
        StringBuilder regex = new StringBuilder();
        boolean inPlaceholder = false;
        StringBuilder placeholder = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                inPlaceholder = true;
                placeholder.setLength(0);
                i++;
                continue;
            }
            if (c == '}') {
                inPlaceholder = false;
                regex.append("[^/]+");
                i++;
                continue;
            }
            if (!inPlaceholder && c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                    // 跳过中间的 /
                    if (i < pattern.length() && pattern.charAt(i) == '/') {
                        regex.append("/?");
                        i++;
                    }
                    continue;
                } else {
                    regex.append("[^/]*");
                    i++;
                    continue;
                }
            }
            if (isRegexSpecial(c)) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
            i++;
        }
        return path.matches(regex.toString());
    }

    private static boolean isRegexSpecial(char c) {
        return c == '.' || c == '^' || c == '$' || c == '+'
                || c == '[' || c == '|' || c == '(' || c == ')' || c == '\\';
    }
}
