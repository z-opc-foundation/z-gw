package com.zifang.z.gw.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 谓词定义 — 描述一个谓词工厂 + 配置参数,例如 {@code Path=/api/**}。
 *
 * <p>与 Spring Cloud Gateway 的 {@code PredicateDefinition} 思路一致:
 * name 是工厂注册名,args 是该工厂需要的参数。
 *
 * <p>谓词语义:对每个请求调用 {@link #apply} 求值,全部谓词命中才算路由匹配。
 */
public final class PredicateDefinition {

    /** 谓词工厂名,例如 {@code Path} / {@code Method} / {@code Header} / {@code Host} / {@code Weight} / {@code Time} */
    private final String name;

    /** 工厂参数 — 例如 Path=/api/**; 多个值用 {@code ,} 分隔或逗号表达式 */
    private final Map<String, String> args;

    private PredicateDefinition(String name, Map<String, String> args) {
        this.name = Objects.requireNonNull(name, "predicate name");
        this.args = args == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public String getName() { return name; }
    public Map<String, String> getArgs() { return args; }

    /** 一次性参数构造 — 当工厂只接收单个字符串时使用 (如 Path=/api/**) */
    public static PredicateDefinition of(String name, String singleArg) {
        return new PredicateDefinition(name, Collections.singletonMap("_genkey_0", singleArg));
    }

    public static PredicateDefinition of(String name, Map<String, String> args) {
        return new PredicateDefinition(name, args);
    }

    @Override
    public String toString() {
        return "PredicateDefinition{" + name + ", args=" + args + '}';
    }
}
