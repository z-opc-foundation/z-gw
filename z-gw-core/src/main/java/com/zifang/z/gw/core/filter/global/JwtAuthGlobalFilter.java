package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.core.config.GatewayProperties;
import com.zifang.z.gw.core.server.GatewayHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * JWT 鉴权全局过滤器 — 把 {@code zgw.security.*} 从"只有配置 schema"接成运行时真正生效的校验。
 *
 * <p>order=0：在 Tracing(-1000)/Metrics(-900)/Logging(-700) 之后、ErrorHandling(900)/NettyProxy(999)
 * 之前。校验失败时<b>不调用 {@code chain.filter}</b>（短路）并直接经
 * {@link GatewayHandler#writeErrorOnce} 写出 401，不把请求交给后端。
 *
 * <p>实现只用 JDK 自带 API（{@code Mac(HmacSHA256)} + Base64URL + 极简 JSON 解析），
 * 不引入任何第三方依赖：
 * <ul>
 *   <li>{@code alg} 必须严格等于 {@code HS256}（字符串），拒绝 {@code none}/{@code RS256} 算法混淆；</li>
 *   <li>签名用 {@link MessageDigest#isEqual} 常量时间比对；</li>
 *   <li>{@code exp}/{@code nbf} 按 {@code jwtLeewaySeconds} 容差校验；</li>
 *   <li>{@code publicPaths} 命中（支持 {@code /**} 通配）免校验；</li>
 *   <li>失败一律 401，响应体是固定文案，不含密钥 / token / 堆栈。</li>
 * </ul>
 *
 * <p>是否注册由 {@code FilterChainBootstrap#installDefaults()} 依据
 * {@code security.enabled} 决定：{@code enabled=false}（或旧构造器未传安全配置）时根本不注册，
 * 与 {@link GatewayProperties#validate()} "显式关闭才放行" 的口径一致。
 */
public class JwtAuthGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    public static final String NAME = "JwtAuth";
    /** 校验成功后 claims 放进 ctx 的 key，供下游过滤器 / 代理使用。 */
    public static final String ATTR_CLAIMS = "auth.claims";

    private static final int ORDER = 0;
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] secret;
    private final List<String> publicPaths;
    private final long leewaySeconds;
    private final LongSupplier nowSeconds;

    public JwtAuthGlobalFilter(GatewayProperties.SecurityConfig security) {
        this(security, () -> System.currentTimeMillis() / 1000L);
    }

    /** 时钟可注入（包可见），让 {@code exp}/{@code nbf} 的 leeway 边界测试确定性。 */
    JwtAuthGlobalFilter(GatewayProperties.SecurityConfig security, LongSupplier nowSeconds) {
        if (security == null) {
            throw new IllegalArgumentException("security config is required");
        }
        String raw = security.getJwtSecret();
        if (raw == null || raw.trim().isEmpty()) {
            // 与 GatewayProperties#validate() 同口径：开着鉴权就必须有密钥，缺了就 fail-fast。
            throw new IllegalStateException("zgw.security.jwt-secret 未配置，无法启用 JwtAuthGlobalFilter");
        }
        this.secret = raw.getBytes(StandardCharsets.UTF_8);
        List<String> pp = security.getPublicPaths();
        this.publicPaths = (pp == null || pp.isEmpty())
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(pp));
        this.leewaySeconds = Math.max(0L, security.getJwtLeewaySeconds());
        this.nowSeconds = nowSeconds == null ? () -> System.currentTimeMillis() / 1000L : nowSeconds;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        if (isPublicPath(ctx.getPath(), publicPaths)) {
            chain.filter(ctx);
            return;
        }
        Map<String, Object> claims = verify(extractBearer(ctx));
        if (claims == null) {
            reject(ctx);          // 短路：写 401，不推进过滤链
            return;
        }
        ctx.setAttribute(ATTR_CLAIMS, claims);
        chain.filter(ctx);
    }

    /**
     * 短路返回 401。设 {@code resp.status} 让外层 Metrics/Logging 记到 401，然后经
     * {@code writeErrorOnce} 真正把响应字节写出去（带一次性闸门，不会双写）。
     *
     * <p>拿不到 Netty 通道时（非标准宿主 / 裸单测）抛 401 异常交给宿主统一兜底：
     * 绝不静默放行，也绝不退化成 500 或无响应。
     */
    private void reject(GatewayContext ctx) {
        ctx.setAttribute("resp.status", "401");
        FullHttpRequest request = ctx.getAttribute("req.original", FullHttpRequest.class);
        ChannelHandlerContext nettyCtx = ctx.getAttribute("netty.ctx", ChannelHandlerContext.class);
        // 固定文案：绝不把密钥 / token / 异常堆栈带进响应体。
        GatewayException ge = new GatewayException.UnauthorizedException("Unauthorized");
        if (request == null || nettyCtx == null) {
            throw ge;
        }
        GatewayHandler.writeErrorOnce(ctx, request, ge);
        log.debug("[{}] JWT auth rejected with 401", ctx.getRequestId());
    }

    // === Bearer 提取 ===

    private String extractBearer(GatewayContext ctx) {
        // GatewayHandler.buildContext 会把每个 header 小写存成 req.header.<name>。
        String header = ctx.getAttribute("req.header.authorization", String.class);
        if (header == null) {
            FullHttpRequest request = ctx.getAttribute("req.original", FullHttpRequest.class);
            if (request != null) {
                header = request.headers().get("Authorization");
            }
        }
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    // === HS256 校验（纯 JDK，手写） ===

    /** @return 校验通过的 claims；任何失败返回 null（由调用方短路成 401）。 */
    private Map<String, Object> verify(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> header = parseJsonObject(decode(parts[0]));
            // 算法混淆防线：alg 必须严格等于 HS256，缺失 / 非字符串 / none / RS256 一律拒绝。
            // 先定算法再验签，避免用非预期算法计算 MAC。
            Object alg = header.get("alg");
            if (!(alg instanceof String) || !"HS256".equals(alg)) {
                return null;
            }
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] expectedSig = hmacSha256(signingInput);
            byte[] providedSig = decode(parts[2]);
            // 常量时间比对，避免时序侧信道逐字节猜签名。
            if (!MessageDigest.isEqual(expectedSig, providedSig)) {
                return null;
            }
            Map<String, Object> claims = parseJsonObject(decode(parts[1]));
            if (!timeClaimsValid(claims)) {
                return null;
            }
            return claims;
        } catch (GeneralSecurityException | IllegalArgumentException | ParseException e) {
            // 解码 / JSON / 密钥 / 签名任何异常都一律 401，绝不让它们冒到链外变成 500。
            return null;
        }
    }

    private byte[] hmacSha256(byte[] signingInput) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(signingInput);
    }

    private static byte[] decode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    /**
     * exp/nbf 容差校验。用 {@code now - exp > leeway} / {@code nbf - now > leeway}
     * 而不是 {@code exp + leeway}，避免超大 exp 造成 long 溢出。
     * exp/nbf 若存在但不是 JSON number，视为格式非法直接拒绝（不静默忽略）。
     */
    private boolean timeClaimsValid(Map<String, Object> claims) {
        long now = nowSeconds.getAsLong();
        if (claims.containsKey("exp")) {
            Object exp = claims.get("exp");
            if (!(exp instanceof Number) || now - ((Number) exp).longValue() > leewaySeconds) {
                return false;
            }
        }
        if (claims.containsKey("nbf")) {
            Object nbf = claims.get("nbf");
            if (!(nbf instanceof Number) || ((Number) nbf).longValue() - now > leewaySeconds) {
                return false;
            }
        }
        return true;
    }

    // === publicPaths 匹配（自写最简 ant 风格，不依赖 Spring） ===

    static boolean isPublicPath(String path, List<String> patterns) {
        if (path == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String p : patterns) {
            if (matchOne(p, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchOne(String pattern, String path) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            // "/api/auth/**" -> 前缀 "/api/auth/"：匹配 /api/auth 本身及 /api/auth/... 任意子路径
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix)
                    || path.equals(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        }
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            StringBuilder re = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    re.append("[^/]*");
                } else if (c == '?') {
                    re.append("[^/]");
                } else {
                    re.append(Pattern.quote(String.valueOf(c)));
                }
            }
            re.append('$');
            return Pattern.matches(re.toString(), path);
        }
        return false;
    }

    // === 极简 JSON 解析（JDK 自带，避免引入任何第三方依赖） ===

    @SuppressWarnings("serial")
    private static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private static Map<String, Object> parseJsonObject(byte[] utf8) throws ParseException {
        P p = new P(new String(utf8, StandardCharsets.UTF_8));
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd() || !(v instanceof Map)) {
            throw new ParseException("not a single JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    /** 递归下降解析器：只需正确解析 object/array/string/number/bool/null，能跳过嵌套声明。 */
    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        boolean atEnd() {
            return i >= s.length();
        }

        Object value() throws ParseException {
            skipWs();
            if (i >= s.length()) {
                throw new ParseException("eof");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw new ParseException("bad value");
            }
        }

        private void expect(String lit) throws ParseException {
            if (!s.regionMatches(i, lit, 0, lit.length())) {
                throw new ParseException("bad literal");
            }
            i += lit.length();
        }

        private Map<String, Object> object() throws ParseException {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new ParseException("bad key");
                }
                String k = string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new ParseException("no colon");
                }
                i++;
                m.put(k, value());
                skipWs();
                if (i >= s.length()) {
                    throw new ParseException("unterminated object");
                }
                char d = s.charAt(i++);
                if (d == '}') {
                    return m;
                }
                if (d != ',') {
                    throw new ParseException("bad separator");
                }
            }
        }

        private List<Object> array() throws ParseException {
            List<Object> a = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(value());
                skipWs();
                if (i >= s.length()) {
                    throw new ParseException("unterminated array");
                }
                char d = s.charAt(i++);
                if (d == ']') {
                    return a;
                }
                if (d != ',') {
                    throw new ParseException("bad separator");
                }
            }
        }

        private String string() throws ParseException {
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new ParseException("bad string");
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new ParseException("bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new ParseException("bad unicode");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new ParseException("bad escape");
                    }
                } else if (c < 0x20) {
                    throw new ParseException("control char in string");
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("unterminated string");
        }

        private Number number() throws ParseException {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean fp = false;
            if (i < s.length() && s.charAt(i) == '.') {
                fp = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                fp = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String num = s.substring(start, i);
            try {
                return fp ? (Number) Double.valueOf(num) : (Number) Long.valueOf(num);
            } catch (NumberFormatException e) {
                throw new ParseException("bad number");
            }
        }
    }
}
