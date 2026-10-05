package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayException;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import com.zifang.z.gw.core.server.GatewayHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错误兜底全局过滤器 — 捕获过滤器链中抛出的异常,转为对应 HTTP 错误响应。
 *
 * <p>这是链上的最外层 try/catch,确保业务异常被规范化处理,不会让 Netty 收到未捕获异常导致连接泄漏。
 *
 * <p><b>此前只记日志并设置 {@code resp.status} 属性,没有真的写响应</b>：而全仓读
 * {@code resp.status} 的只有 {@code MetricsGlobalFilter}（记指标）和
 * {@code LoggingGlobalFilter}（写访问日志），没有任何代码据此往连接写东西。于是本类
 * "捕获异常后吞掉"的效果是——401/403/429/502/503/504 全部<b>一个字节都发不出去</b>，
 * 客户端挂到入站 {@code ReadTimeoutHandler}(60s) 被动断开，拿不到状态码。
 * 单元实测（{@code EmbeddedChannel}）：出站为 {@code null}，通道仍 active。</p>
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
            writeErrorResponse(ctx, ge);
        } catch (Exception e) {
            log.error("[{}] Unhandled exception", ctx.getRequestId(), e);
            ctx.setAttribute("resp.status", "500");
            ctx.setTargetUri(null);
            writeInternalErrorResponse(ctx, e);
        }
    }

    /**
     * 把异常变成真正的 HTTP 响应写出去。
     *
     * <p>拿不到 netty ctx（无 Netty 通道的裸单元场景）或响应已写过时静默跳过——写两次
     * 比不写更糟（客户端会先炸）。</p>
     */
    private void writeErrorResponse(GatewayContext ctx, GatewayException ge) {
        FullHttpRequest request = ctx.getAttribute("req.original", FullHttpRequest.class);
        if (request == null) {
            log.warn("[{}] no original request in ctx, cannot write error response", ctx.getRequestId());
            return;
        }
        if (!GatewayHandler.writeErrorOnce(ctx, request, ge)) {
            log.debug("[{}] response already written, skip error response", ctx.getRequestId());
        }
    }

    private void writeInternalErrorResponse(GatewayContext ctx, Throwable cause) {
        FullHttpRequest request = ctx.getAttribute("req.original", FullHttpRequest.class);
        if (request == null) {
            log.warn("[{}] no original request in ctx, cannot write 500", ctx.getRequestId());
            return;
        }
        if (!GatewayHandler.writeInternalErrorOnce(ctx, request, cause)) {
            log.debug("[{}] response already written, skip 500", ctx.getRequestId());
        }
    }
}
