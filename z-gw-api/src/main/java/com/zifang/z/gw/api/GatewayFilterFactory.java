package com.zifang.z.gw.api;

import java.util.Map;

/**
 * 路由级过滤器工厂 — 通过 yml 配置实例化为 {@link GatewayFilter}。
 *
 * <p>设计参考 Spring Cloud Gateway 的 {@code GatewayFilterFactory}:
 * <ul>
 *   <li>{@link #name()} 是 yml 中的过滤器名</li>
 *   <li>{@link #apply(Map)} 每次路由匹配命中时调用一次,生成该路由的过滤器实例</li>
 * </ul>
 *
 * <p>例:
 * <pre>{@code
 *   public class StripPrefixFilterFactory implements GatewayFilterFactory {
 *       public String name() { return "StripPrefix"; }
 *       public GatewayFilter apply(Map<String, String> args) {
 *           int parts = Integer.parseInt(args.getOrDefault("_genkey_0", "1"));
 *           return new StripPrefixFilter(parts);
 *       }
 *   }
 * }</pre>
 */
public interface GatewayFilterFactory {

    /** yml 配置中引用的过滤器名 */
    String name();

    /**
     * 根据参数构造过滤器实例(每个匹配路由独立一份)
     *
     * @param args 来自 {@link FilterDefinition#getArgs()} 的参数
     * @return 过滤器实例
     */
    GatewayFilter apply(Map<String, String> args);
}
