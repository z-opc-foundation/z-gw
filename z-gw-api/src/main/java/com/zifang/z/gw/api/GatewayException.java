package com.zifang.z.gw.api;

/**
 * 网关异常基类 — 所有 z-gw 内部错误继承此。
 *
 * <p>处理器在过滤器链抛出时会捕获,转为对应 HTTP 状态:
 * <ul>
 *   <li>{@link NotFoundException} → 404</li>
 *   <li>{@link UnauthorizedException} → 401</li>
 *   <li>{@link ForbiddenException} → 403</li>
 *   <li>{@link RateLimitedException} → 429</li>
 *   <li>{@link BadGatewayException} → 502</li>
 *   <li>{@link GatewayTimeoutException} → 504</li>
 *   <li>其他 → 500</li>
 * </ul>
 */
public class GatewayException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public GatewayException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public GatewayException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }

    /** 路由未匹配 */
    public static class NotFoundException extends GatewayException {
        public NotFoundException(String message) {
            super(404, "ROUTE_NOT_FOUND", message);
        }
    }

    /** 未认证 / Token 无效 */
    public static class UnauthorizedException extends GatewayException {
        public UnauthorizedException(String message) {
            super(401, "UNAUTHORIZED", message);
        }
    }

    /** 已认证但无权限 */
    public static class ForbiddenException extends GatewayException {
        public ForbiddenException(String message) {
            super(403, "FORBIDDEN", message);
        }
    }

    /** 限流命中 */
    public static class RateLimitedException extends GatewayException {
        private final long retryAfterSeconds;

        public RateLimitedException(String message, long retryAfterSeconds) {
            super(429, "RATE_LIMITED", message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    /** 后端服务不可达 */
    public static class BadGatewayException extends GatewayException {
        public BadGatewayException(String message, Throwable cause) {
            super(502, "BAD_GATEWAY", message, cause);
        }

        public BadGatewayException(String message) {
            super(502, "BAD_GATEWAY", message);
        }
    }

    /** 后端服务超时 */
    public static class GatewayTimeoutException extends GatewayException {
        public GatewayTimeoutException(String message) {
            super(504, "GATEWAY_TIMEOUT", message);
        }
    }
}
