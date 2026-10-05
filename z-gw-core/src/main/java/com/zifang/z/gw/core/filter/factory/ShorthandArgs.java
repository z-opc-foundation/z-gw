package com.zifang.z.gw.core.filter.factory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把过滤器 args 的两种写法归一，供<b>按语义 key 取值</b>的工厂使用。
 *
 * <p>{@code FilterDefinition.of(name, singleArg)} 把整串收成
 * {@code {"_genkey_0": singleArg}} —— key 是定长占位符（SCG 的写法），
 * 而 yml 简写语法 {@code RequestRateLimiter=replenishRate=10,burstCapacity=20}
 * 走的正是这条路径：配置项名和值都挤在 value 里。
 * map 写法 {@code args: {replenishRate: "10"}} 的 key 才是配置项名。</p>
 *
 * <p>{@code RequestRateLimiter} / {@code Hystrix} / {@code Retry} 此前只认后者，
 * 简写配置一律取不到，于是<b>静默落回默认值</b>——限流配成
 * {@code replenishRate=1,burstCapacity=2} 实际生效的是 10/20，比运维想要的宽得多，
 * 且日志里没有任何异常。</p>
 *
 * <p>同仓的 {@code AddRequestHeaderFilterFactory} / {@code AddResponseHeaderFilterFactory}
 * 早就按 {@code _genkey_0} 拆过自己的参数，谓词侧的 {@code WeightPredicateFactory} /
 * {@code HeaderPredicateFactory} 也已对齐——这里补的是最后三个。</p>
 */
final class ShorthandArgs {

    private ShorthandArgs() {
    }

    /**
     * @return 简写时按 {@code k=v,k=v} 拆好的 map；已是 map 写法时原样返回；入参为 null 返回空 map
     */
    static Map<String, String> normalize(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return Collections.emptyMap();
        }
        String single = args.get("_genkey_0");
        if (single == null) {
            return args;   // map 写法：key 就是配置项名，原样用
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String token : single.split(",")) {
            String t = token.trim();
            int eq = t.indexOf('=');
            if (eq > 0) {
                out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
            // 不含 '=' 的片段不是 k=v 配对，跳过（该工厂的默认值兜底）。
            // 这与 map 写法的行为一致 —— map 里没写的项本来就走默认值。
        }
        return out;
    }
}
