package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错误兜底全局过滤器 — 捕获过滤器链中抛出的异常,转为对应 HTTP 错误响应。
 *
 * <p>这是链上的最外层 try/catch,确保业务异常被规范化处理,不会让 Netty 收到未捕获异常导致连接泄漏。
 */
public class ErrorHandlingGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingGlobalFilter.class);
    public static final String NAME = "ErrorHandling";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 900; // 接近末尾,在 ProxyFilter 之前
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        try {
            chain.filter(ctx);
        } catch (GatewayException ge) {
            log.warn("[{}] GatewayException: status={} code={} msg={}",
                    ctx.getRequestId(), ge.getHttpStatus(), ge.getCode(), ge.getMessage());
            ctx.setAttribute("resp.status", String.valueOf(ge.getHttpStatus()));
            ctx.setAttribute("resp.gateway.exception", ge);
            ctx.setTargetUri(null);  // 阻止 ProxyFilter 再次执行
        } catch (Exception e) {
            log.error("[{}] Unhandled exception", ctx.getRequestId(), e);
            ctx.setAttribute("resp.status", "500");
            ctx.setTargetUri(null);
        }
    }
}
