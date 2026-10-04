package com.zifang.z.gw.core.service;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.RouteDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LbUriResolver} 对上游 scheme 的处理。
 *
 * <p>两条依据都来自本仓代码本身，不是外部约定：</p>
 * <ol>
 *   <li>默认端口由 {@code resolveStatic} 自己写死，而它被 http/https 两个分支共用
 *       ⇒ https 落在 http 的默认端口上。</li>
 *   <li>出站客户端 {@code BackendHttpClient} 的 pipeline 里<b>没有任何 SslHandler</b>
 *       （全仓 {@code grep SslContext|SslHandler} 只命中它自己那行注释），
 *       而它会原样带出客户端的 {@code Authorization} / {@code Cookie}
 *       ⇒ 走 https 的路由实际是<b>明文</b>发凭证。</li>
 * </ol>
 */
class LbUriResolverSchemeTest {

    private static GatewayContext ctxFor(String routeUri) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setPath("/x");
        ctx.setRequestId("t-1");
        ctx.setMatchedRoute(RouteDefinition.builder().id("r1").uri(routeUri).order(1).build());
        return ctx;
    }

    private static LbUriResolver resolver() {
        return LbUriResolver.defaults();
    }

    // ==================================================================
    // http：正常路径
    // ==================================================================

    @Test
    @DisplayName("http:// 无端口时用 80（对照组）")
    void httpDefaultsTo80() {
        LbUriResolver.Resolved r = resolver().resolve(ctxFor("http://a.example.com/api"));
        assertNotNull(r.instance);
        assertEquals(80, r.instance.getPort());
    }

    @Test
    @DisplayName("显式端口按配置来（对照组）")
    void explicitPortIsPreserved() {
        LbUriResolver.Resolved r = resolver().resolve(ctxFor("http://a.example.com:18080/api"));
        assertEquals(18080, r.instance.getPort());
    }

    @Test
    @DisplayName("静态路径正常返回（对照组）")
    void staticPathIsReturned() {
        LbUriResolver.Resolved r = resolver().resolve(ctxFor("http://a.example.com:8080/api/v1"));
        assertEquals("/api/v1", r.path);
    }

    // ==================================================================
    // https：不能静默降级成明文
    // ==================================================================

    @Test
    @DisplayName("https:// 上游必须被明确拒绝，而不是明文转发凭证")
    void httpsUpstreamIsRejectedRatherThanDowngradedToPlaintext() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> resolver().resolve(ctxFor("https://a.example.com/api")),
                "出站客户端没有 TLS，静默接受 https 等于把 Authorization/Cookie 明文发出去");

        assertTrue(e.getMessage().contains("TLS") || e.getMessage().contains("https")
                        || e.getMessage().contains("HTTPS"),
                "错误消息应点明是 https/TLS 未实现: " + e.getMessage());
    }

    @Test
    @DisplayName("https 即使显式写了端口也必须拒绝（拒绝的是协议，不是端口）")
    void httpsWithExplicitPortIsAlsoRejected() {
        assertThrows(RuntimeException.class,
                () -> resolver().resolve(ctxFor("https://a.example.com:443/api")));
    }

    @Test
    @DisplayName("大小写混写的 HtTpS 同样被拒绝（scheme 比较必须忽略大小写）")
    void httpsIsCaseInsensitive() {
        assertThrows(RuntimeException.class,
                () -> resolver().resolve(ctxFor("HtTpS://a.example.com/api")));
    }

    @Test
    @DisplayName("不支持的 scheme 仍按原样报「不支持」")
    void unknownSchemeStillReportsUnsupported() {
        RuntimeException e = assertThrows(IllegalArgumentException.class,
                () -> resolver().resolve(ctxFor("ftp://a.example.com/x")));
        assertTrue(e.getMessage().contains("Unsupported uri scheme"),
                "实际: " + e.getMessage());
    }
}
