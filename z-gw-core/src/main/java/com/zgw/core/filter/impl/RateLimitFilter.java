package com.zgw.core.filter.impl;

import com.zgw.core.filter.Filter;
import com.zgw.core.filter.FilterChain;
import com.zgw.core.ratelimit.RateLimiter;
import com.zgw.core.ratelimit.SlidingWindowRateLimiter;
import com.zgw.core.server.GatewayContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;

/**
 * Rate limiting filter
 */
public class RateLimitFilter implements Filter {

    private static final Logger logger = LogManager.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final String limitKeyType;

    public RateLimitFilter() {
        this(new SlidingWindowRateLimiter(100, Duration.ofMinutes(1)), "ip");
    }

    public RateLimitFilter(RateLimiter rateLimiter, String limitKeyType) {
        this.rateLimiter = rateLimiter;
        this.limitKeyType = limitKeyType;
    }

    @Override
    public String name() {
        return "rateLimit";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public FilterType type() {
        return FilterType.PRE;
    }

    @Override
    public void execute(GatewayContext context, FilterChain chain) {
        String key = extractKey(context);

        if (!rateLimiter.isAllowed(key)) {
            logger.warn("[{}] Rate limit exceeded for key: {}", context.getRequestId(), key);

            RateLimiter.RateLimitStatus status = rateLimiter.getStatus(key);

            context.setResponse(new DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    HttpResponseStatus.TOO_MANY_REQUESTS,
                    io.netty.buffer.Unpooled.copiedBuffer(
                            "{\"error\":\"Rate limit exceeded\",\"retryAfter\":" + status.getRetryAfter() + "}",
                            io.netty.util.CharsetUtil.UTF_8)));

            context.getResponse().headers()
                    .set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON)
                    .set("X-RateLimit-Limit", String.valueOf(status.getLimit()))
                    .set("X-RateLimit-Remaining", String.valueOf(status.getRemaining()))
                    .set("X-RateLimit-Reset", String.valueOf(status.getResetTime()))
                    .set(io.netty.handler.codec.http.HttpHeaderNames.RETRY_AFTER, String.valueOf((int) Math.ceil(status.getRetryAfter() / 1000.0)));

            return;
        }

        // Add rate limit headers to response
        RateLimiter.RateLimitStatus status = rateLimiter.getStatus(key);
        context.setAttribute("rateLimit.limit", status.getLimit());
        context.setAttribute("rateLimit.remaining", status.getRemaining());
        context.setAttribute("rateLimit.reset", status.getResetTime());

        chain.execute(context);
    }

    private String extractKey(GatewayContext context) {
        switch (limitKeyType.toLowerCase()) {
            case "ip":
                return context.getClientIp();
            case "user":
                return context.getAttribute("userId", context.getClientIp());
            case "api":
                return context.getRequest().uri();
            case "global":
                return "global";
            default:
                return context.getClientIp();
        }
    }
}