package com.zifang.z.gw.api;

import java.util.Map;

/**
 * 谓词工厂 SPI — 网关的所有内置/扩展路由谓词都通过这个工厂接口注册。
 *
 * <p>设计融合 Spring Cloud Gateway 的 {@code RoutePredicateFactory}:
 * <ul>
 *   <li>{@link #name()} 是注册名(yml 配置引用)</li>
 *   <li>{@link #apply(GatewayContext, Map)} 每次请求评估一次,返回 {@link PredicateResult}</li>
 * </ul>
 *
 * <p>每个实现类应当是 final + 无状态,通过 {@code ServiceLoader} 或 Spring 容器被发现。
 */
public interface PredicateFactory {

    /** 工厂名,yml 引用 — 例如 {@code Path} / {@code Method} / {@code Header} / {@code Host} / {@code Weight} / {@code Time} */
    String name();

    /**
     * 评估给定上下文是否满足本谓词。
     *
     * @param ctx 网关上下文
     * @param args 来自 {@link PredicateDefinition#getArgs()} 的参数 (可能为空)
     * @return 评估结果,匹配返回 {@link PredicateResult#match()},否则带失败原因
     */
    PredicateResult apply(GatewayContext ctx, Map<String, String> args);
}
