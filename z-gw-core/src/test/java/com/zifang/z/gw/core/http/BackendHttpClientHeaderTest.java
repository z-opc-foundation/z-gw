package com.zifang.z.gw.core.http;

import com.zifang.z.gw.api.GatewayContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站请求头转发契约：<b>不得把 hop-by-hop 头原样带给后端</b>。
 *
 * <p>依据来自本仓的<b>另一个方向</b>：{@code GatewayHandler.writeFullResponse} 处理
 * 后端响应时明确写了 {@code // 去掉 hop-by-hop headers} 并 {@code remove("Transfer-Encoding")}。
 * 请求方向此前<b>没有任何过滤</b>——同一个仓对两个方向的认知不一致。</p>
 *
 * <p>实际危害不是"多带个头"那么轻：{@code buildRequest} 自己会
 * {@code set(CONTENT_LENGTH, content.readableBytes())}，而 body 已被
 * {@code HttpObjectAggregator} 聚合成完整字节。若客户端的
 * {@code Transfer-Encoding: chunked} 一起被转发，出站请求上
 * <b>TE 与 Content-Length 同时存在且互相矛盾</b>——这正是 HTTP 请求走私的经典前提，
 * 前置代理与后端对 body 边界的理解不一致时，前一段请求会被"藏"进后一段的正文里。</p>
 */
class BackendHttpClientHeaderTest {

    private static FullHttpRequest build(Map<String, String> headers, String body) {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("POST");
        ctx.setRequestId("req-1");
        ctx.setAttribute("req.headers", headers);
        if (body != null) {
            BackendHttpClient.setBody(ctx, body);
        }
        try {
            return BackendHttpClient.buildRequest(ctx, "http://backend.internal:8080/api/v1");
        } catch (Exception e) {
            throw new AssertionError("buildRequest 不应抛异常", e);
        }
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================================================================
    // hop-by-hop 头：RFC 7230 §6.1
    // ==================================================================

    @Test
    @DisplayName("Transfer-Encoding 不得转发（与代码自己设的 Content-Length 冲突）")
    void transferEncodingIsNotForwarded() {
        FullHttpRequest req = build(headers("Transfer-Encoding", "chunked"), "hello");
        assertFalse(req.headers().contains("Transfer-Encoding"),
                "Transfer-Encoding 被转发到后端: " + req.headers());
        assertTrue(req.headers().contains("Content-Length"),
                "Content-Length 仍应由本方法按实际 body 设置");
    }

    @Test
    @DisplayName("其余 hop-by-hop 头一律不得转发")
    void otherHopByHopHeadersAreNotForwarded() {
        FullHttpRequest req = build(headers(
                "Connection", "keep-alive",
                "Keep-Alive", "timeout=5",
                "Proxy-Authenticate", "Basic",
                "Proxy-Authorization", "Basic Zm9v",
                "TE", "trailers",
                "Trailer", "Expires",
                "Upgrade", "websocket"), null);

        for (String h : new String[]{"Connection", "Keep-Alive", "Proxy-Authenticate",
                "Proxy-Authorization", "TE", "Trailer", "Upgrade"}) {
            assertFalse(req.headers().contains(h), h + " 是 hop-by-hop 头，不应转发: " + req.headers());
        }
    }

    @Test
    @DisplayName("Connection 头点名的那些头也要剥掉（RFC 7230 §6.1）")
    void headersNamedByConnectionAreStripped() {
        // 客户端说"把 X-Internal-Auth 去掉"——它本来就属于本次连接
        FullHttpRequest req = build(headers(
                "Connection", "X-Internal-Auth, close",
                "X-Internal-Auth", "secret"), null);

        assertFalse(req.headers().contains("X-Internal-Auth"),
                "Connection 点名的头必须一并剥掉: " + req.headers());
    }

    // ==================================================================
    // 对照组：普通端到端头必须照常转发
    // ==================================================================

    @Test
    @DisplayName("普通端到端头照常转发（对照组）")
    void endToEndHeadersAreForwarded() {
        FullHttpRequest req = build(headers(
                "Authorization", "Bearer t",
                "Accept", "application/json",
                "X-Biz-Trace", "abc"), null);

        assertEquals("Bearer t", req.headers().get("Authorization"));
        assertEquals("application/json", req.headers().get("Accept"));
        assertEquals("abc", req.headers().get("X-Biz-Trace"));
    }

    @Test
    @DisplayName("Host 仍被改写为后端地址（对照组：不能被原样透传）")
    void hostIsRewrittenToBackend() {
        FullHttpRequest req = build(headers("Host", "public.example.com"), null);
        assertEquals("backend.internal:8080", req.headers().get("Host"));
    }

    @Test
    @DisplayName("追踪头注入仍然生效（对照组）")
    void tracingHeadersAreInjected() {
        GatewayContext ctx = new GatewayContext();
        ctx.setMethod("GET");
        ctx.setRequestId("req-42");
        ctx.setClientIp("1.2.3.4");
        FullHttpRequest req;
        try {
            req = BackendHttpClient.buildRequest(ctx, "http://backend.internal:8080/x");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertNotNull(req);
        assertEquals("req-42", req.headers().get("X-Request-Id"));
        assertEquals("1.2.3.4", req.headers().get("X-Forwarded-For"));
    }
}
