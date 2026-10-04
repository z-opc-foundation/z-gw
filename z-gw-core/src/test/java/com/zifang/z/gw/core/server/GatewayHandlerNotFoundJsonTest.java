package com.zifang.z.gw.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.gw.core.config.ServerConfig;
import com.zifang.z.gw.core.router.RouteMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayHandler} 的 404 响应必须是<b>合法 JSON</b>。
 *
 * <p>同文件的另外两处 JSON 拼接都走了 {@code escape()}（第 96 / 192 行），
 * 而 404 分支把 {@code ctx.getPath()} 与 {@code ctx.getRequestId()} <b>原样拼进去</b>。
 * 两者都来自请求方可控的输入：path 走 {@code QueryStringDecoder} 会做 URL 解码
 * （{@code %22} → {@code "}），requestId 直接取 {@code X-Request-Id} 头、无任何校验。
 * 结果是攻击者能把任意字段注入网关的错误响应。</p>
 */
class GatewayHandlerNotFoundJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 空路由表 ⇒ route() 返回 null ⇒ 走 404 分支。不需要 mock 任何依赖。 */
    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
                new GatewayHandler(new ServerConfig(), new RouteMatcher(), null, null));
    }

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private static String bodyOf(EmbeddedChannel ch) {
        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp, "handler 应写出响应");
        return resp.content().toString(StandardCharsets.UTF_8);
    }

    // ==================================================================
    // path 注入
    // ==================================================================

    @Test
    @DisplayName("path 含引号时 404 响应体仍是合法 JSON")
    void pathWithQuoteStillYieldsValidJson() throws Exception {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(get("/a%22b"));          // %22 → "

        String body = bodyOf(ch);
        JsonNode node = MAPPER.readTree(body);   // 修复前这里是解析失败
        assertNotNull(node);
    }

    @Test
    @DisplayName("path 里的 JSON 片段不得被提升为顶层字段")
    void pathCannotInjectTopLevelField() throws Exception {
        EmbeddedChannel ch = newChannel();
        // 解码后 path = /a","injected":"pwned
        ch.writeInbound(get("/a%22%2C%22injected%22%3A%22pwned"));

        String body = bodyOf(ch);
        JsonNode node = MAPPER.readTree(body);   // 解析失败会在这里抛
        assertFalse(node.has("injected"),
                "path 被注入成了顶层字段: " + body);
        assertTrue(node.get("message").asText().contains("injected"),
                "原样内容应作为 message 的值出现（而不是结构）: " + body);
    }

    // ==================================================================
    // X-Request-Id 注入（更宽松的入口：header 无需 URL 编码）
    // ==================================================================

    @Test
    @DisplayName("X-Request-Id 含引号时 404 响应体仍是合法 JSON")
    void requestIdWithQuoteStillYieldsValidJson() throws Exception {
        EmbeddedChannel ch = newChannel();
        FullHttpRequest req = get("/nope");
        req.headers().set("X-Request-Id", "abc\"def");

        ch.writeInbound(req);

        String body = bodyOf(ch);
        JsonNode node = MAPPER.readTree(body);   // 修复前这里是解析失败
        assertNotNull(node);
    }

    @Test
    @DisplayName("X-Request-Id 不得注入顶层字段")
    void requestIdCannotInjectTopLevelField() throws Exception {
        EmbeddedChannel ch = newChannel();
        FullHttpRequest req = get("/nope");
        req.headers().set("X-Request-Id", "a\",\"injected\":\"pwned");

        ch.writeInbound(req);

        String body = bodyOf(ch);
        JsonNode node = MAPPER.readTree(body);
        assertFalse(node.has("injected"), "requestId 被注入成了顶层字段: " + body);
    }

    // ==================================================================
    // 对照组
    // ==================================================================

    @Test
    @DisplayName("普通未命中路由：404 + 合法 JSON（对照组）")
    void plainNotFoundStillWorks() throws Exception {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(get("/no-such-route"));

        FullHttpResponse resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        String body = resp.content().toString(StandardCharsets.UTF_8);

        JsonNode node = MAPPER.readTree(body);
        assertEquals("Not Found", node.get("error").asText());
        assertTrue(node.get("message").asText().contains("/no-such-route"));
        assertTrue(node.has("requestId"));
    }

    @Test
    @DisplayName("健康检查与 OPTIONS 不受影响（对照组）")
    void healthAndOptionsUnaffected() {
        EmbeddedChannel health = newChannel();
        health.writeInbound(get("/health"));
        FullHttpResponse hr = health.readOutbound();
        assertEquals(HttpResponseStatus.OK, hr.status());
        assertEquals("{\"status\":\"UP\"}", hr.content().toString(StandardCharsets.UTF_8));

        EmbeddedChannel opts = newChannel();
        FullHttpRequest o = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/anything");
        o.headers().set("Origin", "http://x");
        opts.writeInbound(o);
        FullHttpResponse or = opts.readOutbound();
        assertEquals(HttpResponseStatus.NO_CONTENT, or.status());
    }

    @Test
    @DisplayName("path 里的 tab 必须转义（原始控制字符在 JSON 中非法）")
    void tabInPathIsEscaped() throws Exception {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(get("/a%09b"));          // %09 → tab

        String body = bodyOf(ch);
        assertFalse(body.contains("\u0009"), "响应体里出现了原始 tab 字符: " + body);
        JsonNode node = MAPPER.readTree(body);
        assertTrue(node.get("message").asText().contains("\t"),
                "tab 应以转义序列出现在 message 里: " + body);
    }

    @Test
    @DisplayName("path 里的反斜杠不得破坏 JSON 结构")
    void backslashInPathIsEscaped() throws Exception {
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(get("/a%5Ca"));          // %5C → \，而 \a 不是合法 JSON 转义

        String body = bodyOf(ch);
        JsonNode node = MAPPER.readTree(body);   // 修复前这里是解析失败
        assertTrue(node.get("message").asText().contains("\\a"),
                "原样内容应作为 message 的值出现: " + body);
    }
}
