package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.filter.FilterChainBootstrap;
import com.zifang.z.gw.core.router.FilterAssembler;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.gw.core.server.GatewayHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtAuthGlobalFilter} 的接线与 HS256 校验用例。
 *
 * <p>密钥与 token 全部运行时随机生成，仓内不落任何密钥/token 字面值。
 * 校验失败在"裸 ctx（无 Netty 通道）"下以抛 {@code 401 UnauthorizedException} 表达，
 * 由宿主统一兜底；真实通道下由过滤器自己写出 401 —— 后者见
 * {@link #endToEndUnauthorizedWrites401Response()}。
 */
class JwtAuthGlobalFilterTest {

    private static final byte[] SECRET = (UUID.randomUUID() + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = (UUID.randomUUID() + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    /** 固定时钟（秒），让 exp/nbf/leeway 边界完全确定。 */
    private static final long NOW = 1_700_000_000L;

    private static final String HS256_HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    // ==================== 校验主流程 ====================

    @Test
    @DisplayName("有效 HS256 token：放行并把 claims 放进 ctx 供下游使用")
    void validTokenPassesAndExposesClaims() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW + 3600, null), SECRET)));

        RecordingChain chain = new RecordingChain();
        f.filter(c, chain);

        assertTrue(chain.advanced, "校验通过必须推进过滤链");
        assertNotNull(c.getAttribute(JwtAuthGlobalFilter.ATTR_CLAIMS), "成功应把 claims 放进 ctx attribute");
    }

    @Test
    @DisplayName("过期 token（exp 超过容差）：短路 401")
    void expiredTokenIsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW - 1, null), SECRET)));

        assertRejected(f, c);
    }

    @Test
    @DisplayName("错误签名：短路 401（常量时间比对不得放行）")
    void wrongSignatureIsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW + 3600, null), OTHER_SECRET)));

        assertRejected(f, c);
    }

    @Test
    @DisplayName("alg=none：短路 401（拒绝算法混淆）")
    void algNoneIsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt("{\"alg\":\"none\"}", payload(NOW + 3600, null), SECRET)));

        assertRejected(f, c);
    }

    @Test
    @DisplayName("alg=RS256（却用 HS 签名）：短路 401（拒绝算法混淆）")
    void algRs256IsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt("{\"alg\":\"RS256\"}", payload(NOW + 3600, null), SECRET)));

        assertRejected(f, c);
    }

    @Test
    @DisplayName("缺 Authorization 头：短路 401")
    void missingAuthorizationHeaderIsRejected() {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));

        assertRejected(f, ctx("/api/orders", null));
    }

    @Test
    @DisplayName("格式错的 token（非 3 段 / 段内容非法）：短路 401")
    void malformedTokenIsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));

        assertRejected(f, ctx("/api/orders", "Bearer not-a-jwt"));
        assertRejected(f, ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW + 3600, null), SECRET) + "x")));
        assertRejected(f, ctx("/api/orders", "Basic abc"));  // 非 Bearer 前缀
    }

    @Test
    @DisplayName("nbf 未生效（超过容差）：短路 401")
    void nbfNotYetValidIsRejected() throws Exception {
        JwtAuthGlobalFilter f = filter(cfg(true, 0));
        GatewayContext c = ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(null, NOW + 1), SECRET)));

        assertRejected(f, c);
    }

    @Test
    @DisplayName("leeway 边界：恰好等于容差放行，多 1 秒即 401")
    void leewayBoundary() throws Exception {
        long leeway = 60;
        JwtAuthGlobalFilter f = filter(cfg(true, leeway));

        // exp：now-exp == leeway 有效；== leeway+1 过期
        assertAccepted(f, ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW - leeway, null), SECRET))));
        assertRejected(f, ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(NOW - leeway - 1, null), SECRET))));
        // nbf：nbf-now == leeway 有效；== leeway+1 未生效
        assertAccepted(f, ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(null, NOW + leeway), SECRET))));
        assertRejected(f, ctx("/api/orders", bearer(jwt(HS256_HEADER, payload(null, NOW + leeway + 1), SECRET))));
    }

    // ==================== publicPaths ====================

    @Test
    @DisplayName("publicPaths 命中（精确 + /** 前缀 + /** 自身）免校验放行；未命中仍 401")
    void publicPathBypassesJwt() {
        JwtAuthGlobalFilter f = filter(cfg(true, 0, "/health", "/api/public/**"));

        for (String path : new String[]{"/health", "/api/public/ping", "/api/public", "/api/public/a/b"}) {
            GatewayContext c = ctx(path, null);   // 故意不带 token
            RecordingChain chain = new RecordingChain();
            f.filter(c, chain);
            assertTrue(chain.advanced, "publicPath 命中的 " + path + " 必须免校验放行");
            assertNull(c.getAttribute(JwtAuthGlobalFilter.ATTR_CLAIMS), "免校验路径不应凭空塞 claims");
        }
        assertRejected(f, ctx("/api/private/ping", null));   // 对照：非公开路径仍要拦
    }

    // ==================== 接线（installDefaults） ====================

    @Test
    @DisplayName("security.enabled=false：installDefaults 不注册 JwtAuth（显式关闭才放行）")
    void disabledSecurityDoesNotRegisterFilter() {
        FilterAssembler a = new FilterAssembler();
        GatewayProperties.SecurityConfig s = new GatewayProperties.SecurityConfig();
        s.setEnabled(false);
        s.setJwtSecret(null);   // 关闭时不要求密钥，也不该在这里炸

        new FilterChainBootstrap(a, new ServerConfig(), s).installDefaults();

        assertFalse(a.getGlobalFilters().stream().anyMatch(g -> JwtAuthGlobalFilter.NAME.equals(g.name())),
                "enabled=false 时链里不该有 JwtAuthGlobalFilter");
        assertEquals(5, a.getGlobalFilters().size(), "关闭时默认全局过滤器仍是原来的 5 个");
    }

    @Test
    @DisplayName("security.enabled=true：installDefaults 注册 JwtAuth，order=0 且按升序排在 Tracing 后、Proxy 前")
    void enabledTrueRegistersFilterInOrder() {
        FilterAssembler a = new FilterAssembler();
        new FilterChainBootstrap(a, new ServerConfig(), cfg(true, 0)).installDefaults();

        GatewayFilter jwt = a.getGlobalFilters().stream()
                .filter(g -> JwtAuthGlobalFilter.NAME.equals(g.name())).findFirst().orElse(null);
        assertNotNull(jwt, "enabled=true 时链里必须有 JwtAuthGlobalFilter");
        assertEquals(0, jwt.order());

        List<GatewayFilter> g = a.getGlobalFilters();
        assertEquals(6, g.size(), "开启时默认全局过滤器应为 6 个");
        for (int i = 1; i < g.size(); i++) {
            assertTrue(g.get(i - 1).order() <= g.get(i).order(), "全局过滤器必须按 order 升序");
        }
        int jwtIdx = g.indexOf(jwt);
        assertTrue(jwtIdx > 0, "JwtAuth 不能排在最前（须在 Tracing 之后）");
        assertTrue(jwtIdx < g.size() - 1, "JwtAuth 不能排在最后（须在 Proxy 之前）");
    }

    // ==================== 端到端：真的写出 401 ====================

    @Test
    @DisplayName("端到端：无 token 请求真的收到 HTTP 401 响应体，且不泄露密钥/token/堆栈")
    void endToEndUnauthorizedWrites401Response() throws Exception {
        FilterAssembler assembler = new FilterAssembler();
        assembler.addGlobalFilter(new JwtAuthGlobalFilter(cfg(true, 0)));
        RouteMatcher matcher = new RouteMatcher();
        matcher.refresh(Collections.singletonList(
                RouteDefinition.builder().id("r1").uri("http://127.0.0.1:1/x").order(1).build()));
        EmbeddedChannel ch = new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), matcher, assembler, null));

        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x"));

        FullHttpResponse resp = awaitOutbound(ch);
        assertNotNull(resp, "校验失败必须真的写出 401，不能是 null（无响应）");
        assertEquals(401, resp.status().code(), "必须是 401，不是 500 或其它");
        String body = resp.content().toString(StandardCharsets.UTF_8);
        assertFalse(body.contains(new String(SECRET, StandardCharsets.UTF_8)), "响应体泄露了密钥");
        assertFalse(body.contains("Bearer"), "响应体回显了 token 前缀");
        assertFalse(body.toLowerCase().contains("exception"), "响应体泄露了异常堆栈信息");
        assertTrue(body.contains("UNAUTHORIZED"), "响应体应是固定的 401 文案");
        resp.release();
    }

    // ==================== 基础设施 ====================

    private static GatewayProperties.SecurityConfig cfg(boolean enabled, long leeway, String... publicPaths) {
        GatewayProperties.SecurityConfig s = new GatewayProperties.SecurityConfig();
        s.setEnabled(enabled);
        s.setJwtSecret(new String(SECRET, StandardCharsets.UTF_8));
        s.setJwtLeewaySeconds(leeway);
        if (publicPaths.length > 0) {
            s.setPublicPaths(Arrays.asList(publicPaths));
        }
        return s;
    }

    private static JwtAuthGlobalFilter filter(GatewayProperties.SecurityConfig cfg) {
        return new JwtAuthGlobalFilter(cfg, () -> NOW);
    }

    private static GatewayContext ctx(String path, String authHeader) {
        GatewayContext c = new GatewayContext();
        c.setPath(path);
        c.setRequestId("test-req");
        if (authHeader != null) {
            c.setAttribute("req.header.authorization", authHeader);
        }
        return c;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 按给定 header/payload JSON 与签名密钥造一个 HS256 token。 */
    private static String jwt(String headerJson, String payloadJson, byte[] signKey) throws Exception {
        String h = b64(headerJson.getBytes(StandardCharsets.UTF_8));
        String p = b64(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = h + "." + p;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signKey, "HmacSHA256"));
        byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64(sig);
    }

    private static String payload(Long exp, Long nbf) {
        StringBuilder sb = new StringBuilder("{");
        if (exp != null) {
            sb.append("\"exp\":").append(exp);
        }
        if (nbf != null) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("\"nbf\":").append(nbf);
        }
        if (sb.length() > 1) {
            sb.append(',');
        }
        sb.append("\"sub\":\"unit-test\"}");
        return sb.toString();
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void assertAccepted(JwtAuthGlobalFilter f, GatewayContext c) {
        RecordingChain chain = new RecordingChain();
        f.filter(c, chain);
        assertTrue(chain.advanced, "校验通过必须推进过滤链");
    }

    private static void assertRejected(JwtAuthGlobalFilter f, GatewayContext c) {
        RecordingChain chain = new RecordingChain();
        GatewayException.UnauthorizedException ex = assertThrows(
                GatewayException.UnauthorizedException.class, () -> f.filter(c, chain),
                "校验失败必须拒绝，不能静默放行");
        assertEquals(401, ex.getHttpStatus(), "校验失败必须是 401，不是 500/其它");
        assertFalse(chain.advanced, "校验失败必须短路，不得推进过滤链");
        assertEquals("401", c.getAttribute("resp.status", String.class), "应标记 resp.status 供外层记录");
    }

    private static FullHttpResponse awaitOutbound(EmbeddedChannel ch) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            FullHttpResponse r = ch.readOutbound();
            if (r != null) {
                return r;
            }
            Thread.sleep(10);
        }
        return null;
    }

    /** 记录链是否被推进。 */
    private static final class RecordingChain implements GatewayFilterChain {
        private boolean advanced;

        @Override
        public void filter(GatewayContext ctx) {
            advanced = true;
        }
    }
}
