package com.zgw.core.filter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgw.core.filter.Filter;
import com.zgw.core.filter.FilterChain;
import com.zgw.core.server.GatewayContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Authentication filter
 */
public class AuthFilter implements Filter {

    private static final Logger logger = LogManager.getLogger(AuthFilter.class);

    private final Map<String, AuthProvider> authProviders;
    private final Set<String> publicPaths;
    private final Function<String, Boolean> tokenValidator;

    public AuthFilter() {
        this.authProviders = new HashMap<>();
        this.publicPaths = new HashSet<>();
        this.tokenValidator = null;

        // Add default auth providers
        authProviders.put("bearer", new BearerAuthProvider());
        authProviders.put("basic", new BasicAuthProvider());
        authProviders.put("apikey", new ApiKeyAuthProvider());

        // Add default public paths
        publicPaths.add("/health");
        publicPaths.add("/healthz");
        publicPaths.add("/actuator/health");
        publicPaths.add("/api/v1/auth/login");
        publicPaths.add("/api/v1/auth/register");
        // ========== z-ctc 用户自助通道（FEATURE011）==========
        // 注册 / 登录 / 找回密码 / 发送验证码 — 无需 token
        publicPaths.add("/api/ctc/user/send-code");
        publicPaths.add("/api/ctc/user/register");
        publicPaths.add("/api/ctc/user/login");
        publicPaths.add("/api/ctc/user/reset-password");
        // ========== z-ctc authn 验证码体系（注册/手机验证码登录/找回密码）==========
        publicPaths.add("/api/ctc/authn/send-code");
        publicPaths.add("/api/ctc/authn/phone-login");
        publicPaths.add("/api/ctc/authn/register-phone");
        publicPaths.add("/api/ctc/authn/reset-password");
    }

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public FilterType type() {
        return FilterType.PRE;
    }

    @Override
    public boolean shouldFilter(GatewayContext context) {
        // Skip auth for public paths
        String path = context.getRequest().uri();
        for (String publicPath : publicPaths) {
            if (path.startsWith(publicPath)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void execute(GatewayContext context, FilterChain chain) {
        String authHeader = context.getRequest().headers().get(io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION);

        if (authHeader == null || authHeader.isEmpty()) {
            logger.warn("[{}] Authentication required but no authorization header", context.getRequestId());
            sendUnauthorized(context, "Missing authorization header");
            return;
        }

        // Parse auth scheme
        String[] parts = authHeader.split(" ", 2);
        String scheme = parts[0].toLowerCase();
        String credentials = parts.length > 1 ? parts[1] : "";

        AuthProvider provider = authProviders.get(scheme);
        if (provider == null) {
            logger.warn("[{}] Unsupported authentication scheme: {}", context.getRequestId(), scheme);
            sendUnauthorized(context, "Unsupported authentication scheme");
            return;
        }

        AuthResult result = provider.authenticate(credentials, context);
        if (!result.isSuccess()) {
            logger.warn("[{}] Authentication failed: {}", context.getRequestId(), result.getMessage());
            sendUnauthorized(context, result.getMessage());
            return;
        }

        // Authentication successful
        context.setAttribute("userId", result.getUserId());
        context.setAttribute("userRoles", result.getRoles());
        context.setAttribute("authScheme", scheme);

        logger.debug("[{}] Authentication successful for user: {}", context.getRequestId(), result.getUserId());

        chain.execute(context);
    }

    private void sendUnauthorized(GatewayContext context, String message) {
        context.setResponse(new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                io.netty.buffer.Unpooled.copiedBuffer(
                        "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}",
                        io.netty.util.CharsetUtil.UTF_8)));

        context.getResponse().headers()
                .set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON)
                .set(io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"gateway\", Basic realm=\"gateway\"");
    }

    /**
     * Authentication provider interface
     */
    public interface AuthProvider {
        AuthResult authenticate(String credentials, GatewayContext context);
    }

    /**
     * Bearer token authentication (FEATURE055 · D02 部分整改).
     * <p>
     * <b>已加严</b>:
     * <ul>
     *   <li>校验 token 必须是合法 JWT 结构 ({@code header.payload.signature}, 三段 base64url);</li>
     *   <li>校验 JWT header 的 {@code alg} 字段, 禁止 {@code "none"} / 空算法;</li>
     *   <li>校验 payload 必须含 {@code exp} 过期时间, 且未过期;</li>
     *   <li>从 payload 中安全提取 {@code userId} / {@code roles} claim, 不再做"任意 hashCode 作 userId".</li>
     * </ul>
     * <b>仍未做</b> (待 FEATURE056 接入 z-ctc-sso 共享密钥后落地):
     * HS256 签名真实验签 (当前仍 trust signature 字段); 此项依赖 z-gw 与 z-ctc-sso
     * 共用同一 JWT secret, 需先在 z-gw 配置中心注入密钥再启用签名校验.
     */
    public static class BearerAuthProvider implements AuthProvider {
        @Override
        public AuthResult authenticate(String credentials, GatewayContext context) {
            if (credentials == null || credentials.isEmpty()) {
                return AuthResult.failure("Invalid token");
            }
            // 1) 结构校验: 三段 base64url, 以 . 分隔
            String[] parts = credentials.split("\\.");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                return AuthResult.failure("Malformed JWT (expect header.payload.signature)");
            }
            // 2) 解 header, 校验 alg
            JsonNode header;
            JsonNode payload;
            try {
                header = decodeJwtJsonPart(parts[0]);
                payload = decodeJwtJsonPart(parts[1]);
            } catch (Exception e) {
                return AuthResult.failure("JWT base64url decode failed");
            }
            JsonNode algNode = header.get("alg");
            if (algNode == null || algNode.isNull()) {
                return AuthResult.failure("JWT missing alg header");
            }
            String alg = algNode.asText("");
            if ("none".equalsIgnoreCase(alg) || alg.isEmpty()) {
                return AuthResult.failure("JWT alg=none rejected");
            }
            if (!"HS256".equalsIgnoreCase(alg) && !"HS384".equalsIgnoreCase(alg) && !"HS512".equalsIgnoreCase(alg)
                    && !"RS256".equalsIgnoreCase(alg) && !"RS384".equalsIgnoreCase(alg) && !"RS512".equalsIgnoreCase(alg)) {
                return AuthResult.failure("JWT unsupported alg: " + alg);
            }
            // 3) 校验 exp
            JsonNode expNode = payload.get("exp");
            if (expNode == null || !expNode.isNumber()) {
                return AuthResult.failure("JWT missing exp claim");
            }
            long exp = expNode.asLong(0);
            if (exp > 0 && exp < (System.currentTimeMillis() / 1000L)) {
                return AuthResult.failure("JWT expired");
            }
            // 4) 提取 userId / roles
            String userId = payload.has("userId") ? payload.get("userId").asText("") : "";
            if (userId.isEmpty()) {
                userId = payload.has("sub") ? payload.get("sub").asText("") : "";
            }
            if (userId.isEmpty()) {
                return AuthResult.failure("JWT missing userId/sub claim");
            }
            String[] roles = new String[]{"USER"};
            if (payload.has("roles") && payload.get("roles").isArray() && payload.get("roles").size() > 0) {
                roles = new String[payload.get("roles").size()];
                for (int i = 0; i < roles.length; i++) {
                    roles[i] = payload.get("roles").get(i).asText("USER");
                }
            }
            return AuthResult.success(userId, roles);
        }

        private JsonNode decodeJwtJsonPart(String base64Url) {
            String json = new String(java.util.Base64.getUrlDecoder().decode(base64Url),
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            } catch (Exception e) {
                throw new RuntimeException("decode failed", e);
            }
        }
    }

    /**
     * Basic authentication
     */
    public static class BasicAuthProvider implements AuthProvider {
        @Override
        public AuthResult authenticate(String credentials, GatewayContext context) {
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(credentials));
                String[] parts = decoded.split(":", 2);

                if (parts.length != 2) {
                    return AuthResult.failure("Invalid credentials format");
                }

                String username = parts[0];
                String password = parts[1];

                // TODO: Validate against user database
                if (validateCredentials(username, password)) {
                    return AuthResult.success(username, new String[]{"USER"});
                }

                return AuthResult.failure("Invalid username or password");
            } catch (Exception e) {
                return AuthResult.failure("Invalid credentials");
            }
        }

        private boolean validateCredentials(String username, String password) {
            // TODO: Implement proper credential validation
            return !username.isEmpty() && !password.isEmpty();
        }
    }

    /**
     * API Key authentication
     */
    public static class ApiKeyAuthProvider implements AuthProvider {
        @Override
        public AuthResult authenticate(String credentials, GatewayContext context) {
            // TODO: Validate API key
            if (credentials == null || credentials.isEmpty()) {
                return AuthResult.failure("Invalid API key");
            }

            return AuthResult.success("api_" + credentials.substring(0, Math.min(8, credentials.length())),
                    new String[]{"API"});
        }
    }
}