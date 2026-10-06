package com.zifang.z.gw.core.config;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 出站 CORS 策略 —— 从 {@link ServerConfig} 的 {@code cors-*} 五个字段派生。
 *
 * <p>那五个字段此前<b>全部只有声明和 getter，零读取点</b>：{@code corsEnabled}（默认 false）
 * 没人问过，{@code corsAllowedOrigins} / {@code corsAllowedMethods} /
 * {@code corsAllowedHeaders} / {@code corsMaxAge} 也一样。实际的响应头全是写死的
 * {@code ACAO: *}，而且<b>无条件</b>写 —— 于是 {@code corsEnabled: false} 这个
 * "默认关闭跨域"的声明从来没生效过，网关对外一直是无条件放行任意来源。</p>
 *
 * <p>现在是派生的不可变值对象：{@link GatewayHandler} 在构造时算一次（配置是启动时读的，
 * 逐请求重解析逗号列表纯属浪费），经 {@link #ATTR} 挂在 {@code GatewayContext} 上，
 * 各处写出响应的地方按需取用。</p>
 *
 * <p><b>Origin 白名单的语义</b>：配 {@code *} 就回 {@code *}（本仓从不发
 * {@code Access-Control-Allow-Credentials}，所以这不是凭据泄露面）；配具体列表就
 * <b>回显</b>命中的那个 Origin，不在列表里就<b>一个 ACAO 都不写</b>——让浏览器自己去拦。
 * 回显具体 Origin 时会补 {@code Vary: Origin}，否则 CDN 会把 A 站的响应喂给 B 站。</p>
 */
public final class CorsPolicy {

    /** {@code GatewayContext} 上挂本策略的 attribute 键。 */
    public static final String ATTR = "gw.cors.policy";

    /** 关闭跨域：不写任何 CORS 响应头。 */
    public static final CorsPolicy DISABLED =
            new CorsPolicy(false, true, Collections.<String>emptySet(), null, null, 0L);

    private final boolean enabled;
    private final boolean allowAnyOrigin;
    private final Set<String> allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final long maxAge;

    private CorsPolicy(boolean enabled, boolean allowAnyOrigin, Set<String> allowedOrigins,
                       String allowedMethods, String allowedHeaders, long maxAge) {
        this.enabled = enabled;
        this.allowAnyOrigin = allowAnyOrigin;
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
        this.maxAge = maxAge;
    }

    public static CorsPolicy from(ServerConfig cfg) {
        if (cfg == null || !cfg.isCorsEnabled()) {
            return DISABLED;
        }
        Set<String> origins = new LinkedHashSet<>();
        boolean any = false;
        if (cfg.getCorsAllowedOrigins() != null) {
            for (String raw : cfg.getCorsAllowedOrigins().split(",")) {
                String o = raw.trim();
                if (o.isEmpty()) {
                    continue;
                }
                if ("*".equals(o)) {
                    any = true;
                } else {
                    origins.add(o);
                }
            }
        }
        // 一个都没配 = 不允许任何跨域来源（而不是放行全部）——空列表的语义必须是"空"，
        // 否则"配漏了"会静默变成"全放行"，那正好是这次要修掉的方向。
        return new CorsPolicy(true, any, Collections.unmodifiableSet(origins),
                cfg.getCorsAllowedMethods(), cfg.getCorsAllowedHeaders(), cfg.getCorsMaxAge());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAllowedMethods() {
        return allowedMethods;
    }

    public String getAllowedHeaders() {
        return allowedHeaders;
    }

    public long getMaxAge() {
        return maxAge;
    }

    /**
     * 该给这次请求回什么 {@code Access-Control-Allow-Origin}。
     *
     * @return {@code "*"}、回显的 Origin，或 {@code null}（<b>不要写这个头</b>）
     */
    public String allowOriginFor(String requestOrigin) {
        if (!enabled) {
            return null;
        }
        if (allowAnyOrigin) {
            return "*";
        }
        if (requestOrigin == null) {
            return null;
        }
        // 精确匹配，不做大小写折叠：CORS 的 Origin 由浏览器按规范形式发出，
        // 折叠大小写只会无谓地放宽白名单。
        return allowedOrigins.contains(requestOrigin.trim()) ? requestOrigin.trim() : null;
    }

    /**
     * 给<b>实际响应</b>（不是预检）补 CORS 头。
     *
     * <p>按规范浏览器读的是这一份上的 ACAO，预检那份不算数。已关闭跨域时什么都不做。</p>
     */
    public void applyActual(HttpHeaders headers, String requestOrigin) {
        if (!enabled) {
            return;
        }
        String allow = allowOriginFor(requestOrigin);
        if (allow == null) {
            return;
        }
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allow);
        if (!"*".equals(allow)) {
            // 回显具体 Origin 时必须标 Vary：代理/缓存否则会把这一份喂给别的站点
            appendVary(headers, "Origin");
        }
    }

    /** 给<b>预检</b>响应补 CORS 头。 */
    public void applyPreflight(HttpHeaders headers, String requestOrigin) {
        if (!enabled) {
            return;
        }
        String allow = allowOriginFor(requestOrigin);
        if (allow != null) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allow);
            if (!"*".equals(allow)) {
                appendVary(headers, "Origin");
            }
        }
        // Origin 不在白名单时不写 ACAO（浏览器据此拦掉），但仍把"网关允许什么"如实报出去，
        // 方便 curl 直接调预检时看懂是哪里被拒了。
        if (allowedMethods != null) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, allowedMethods);
        }
        if (allowedHeaders != null) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, allowedHeaders);
        }
        headers.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, String.valueOf(Math.max(0L, maxAge)));
        appendVary(headers, "Access-Control-Request-Method");
        appendVary(headers, "Access-Control-Request-Headers");
    }

    private static void appendVary(HttpHeaders headers, String value) {
        String existing = headers.get(HttpHeaderNames.VARY);
        if (existing == null) {
            headers.set(HttpHeaderNames.VARY, value);
            return;
        }
        for (String v : Arrays.asList(existing.split(","))) {
            if (value.equalsIgnoreCase(v.trim())) {
                return;
            }
        }
        headers.set(HttpHeaderNames.VARY, existing + ", " + value);
    }

    /** 只用于日志/自描述的允许来源清单。 */
    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public String toString() {
        return "CorsPolicy{enabled=" + enabled
                + ", anyOrigin=" + allowAnyOrigin
                + ", origins=" + allowedOrigins
                + ", methods=" + allowedMethods
                + ", headers=" + allowedHeaders
                + ", maxAge=" + maxAge + "}";
    }
}
