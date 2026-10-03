package com.zifang.z.gw.starter.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.gw.core.predicate.PredicateFactoryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API 网关 (z-gw) 管理面薄代理: 把 z-gw-admin 的 {@code /gw/admin/**} REST 面挂到
 * z-opc 的统一前缀 {@code /api/gw/**} 下 (TASK-20260924-007)。
 *
 * <p><b>z-gw 的形态和 z-vector 不同</b>: z-gw-admin 是普通 Spring MVC {@code @RestController}
 * (RouteAdminController=/gw/admin/routes, MetricsController=/gw/admin/metrics, MetaController=/gw/admin/meta),
 * 一旦 {@code io.github.yuku123:z-gw-admin} 进入 classpath, 它 jar 里的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 会自动注册 {@code ZGatewayAdminAutoConfiguration}(@ComponentScan("com.zifang.z.gw.admin")),
 * 这 12 条 handler 就直接出现在本机 {@code /actuator/mappings} —— <b>不需要跨端口转发</b>。
 * 那为什么这里还要一层代理: 前端只有一个 baseURL={@code /api} 的 axios 实例、vite dev 只代理 {@code /api}(约束 7),
 * 而裸 {@code /gw/**} 不在 {@code sso.intercept-paths=/**\/api/**,\/agent\/**} 的覆盖范围内 ——
 * 直接让页面打 /gw/admin/** 等于把管理面继续留在无鉴权前缀上 (TASK-20260924-018 的同型问题)。
 * 所以本 controller 做的是「8888 自己 -> 8888 的 /gw/admin 环回转发」, 唯一作用是把这条面
 * 搬进 {@code /api} 的鉴权与代理语义里, 并统一 JSON 校验。
 *
 * <p><b>只转发 GET</b>: /gw/admin/routes 的 POST/PUT/DELETE/reload 是对本 JVM 在线路由表的整体替换,
 * 孵化页只需读面 (路由列表/详情/指标); 写口一旦透出, 一个误操作 reload 会把 z-gw 的在线路由清零,
 * 这不是孵化轮该开的口子。
 *
 * <p><b>为什么带 {@link #predicateFactoryRegistry()}</b>: z-gw-admin 的 MetaController 构造参数里有
 * {@code PredicateFactoryRegistry}, 而 z-gw-spring-boot-starter 1.0.1/1.0.2 都<b>没有</b>声明这个 @Bean
 * (上游自己知道: z-gw-examples 的 ZGatewayExamplesPatchConfig 注释原话
 * "z-gw-spring-boot-starter 漏掉了 PredicateFactoryRegistry 的 @Bean 声明, 导致 MetaController 启动失败")。
 * 缺这个补丁 bean 的话, 加完依赖后进程<b>直接起不来</b> (NoSuchBeanDefinitionException, z-llm
 * globalExceptionHandler 冲突的同型事故)。这里用 {@code getInstance()} 而不是 {@code new}:
 * RouteMatcher 的默认构造走的就是同一个单例, meta/predicates 与运行时匹配器保证同源。
 *
 * <p>环回转发带非 JSON 熔断: MainWebConfig 之后若把 {@code /gw/**} 加进 SPA fallback, 未接通时
 * GET /gw/admin/* 会命中 {@code forward:/index.html} 拿回 200+HTML, 页面端就是熟悉的
 * {@code SyntaxError: Unexpected token '<'}; 本代理检测到非 JSON 直接报 502, 不把 HTML 当数据透传。
 *
 * <p>Java 源码级 1.8 ⇒ HttpURLConnection, 不用 java.net.http。
 */
@RestController
public class GwProxyController {

    private static final String PREFIX = "/api/gw";
    private static final String UPSTREAM_PREFIX = "/gw/admin";
    /** 子路径白名单: 路由 id / 度量段允许 [A-Za-z0-9_.-] 与 '/', 但显式拒绝 ".." */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9_\\-./]*$");
    private static final Pattern SAFE_QUERY = Pattern.compile("^[A-Za-z0-9_\\-.=&%:/]*$");
    private static final int LOOP_TIMEOUT_MS = 5000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** z-gw Netty 数据面端口 (application.properties: zgw.server.port=9090), 只用于 __instance 的 TCP 自证 */
    @Value("${z.gw.server.port:9090}")
    private int nettyPort;

    /** z-gw starter 开关的 Spring 解析值 (matchIfMissing=true ⇒ 缺省即 true); __instance 自证用 */
    @Value("${z.gw.enabled:true}")
    private boolean zgwEnabled;

    /** 见类注释: 补 z-gw-spring-boot-starter 缺失的 @Bean, 否则 z-gw-admin 一通 MetaController 就炸启动 */
    @Bean
    public PredicateFactoryRegistry predicateFactoryRegistry() {
        return PredicateFactoryRegistry.getInstance();
    }

    /**
     * 自省接口: "admin 面接没接通 / Netty 在不在 / 指标桥不桥" 三件事在这里分开回答,
     * 页面才不至于把 404 (依赖没接) 渲染成 "网关没有路由"。
     */
    @GetMapping(PREFIX + "/__instance")
    public void instance(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jvm", ManagementFactory.getRuntimeMXBean().getName());
        out.put("localPort", req.getLocalPort());
        out.put("proxyPrefix", PREFIX);
        out.put("upstreamPrefix", UPSTREAM_PREFIX);
        out.put("zgwEnabled", Boolean.valueOf(zgwEnabled));
        out.put("nettyPort", Integer.valueOf(nettyPort));
        out.put("nettyAcceptingNow", Boolean.valueOf(tcpConnectable("127.0.0.1", nettyPort)));

        // 探针 1: admin 面本身 (pom 里没加 z-gw-admin 时这里就是 404 —— 那是"没接通", 不是"没有路由")
        // ⚠ 必须带调用方凭证: sso.intercept-paths 加了 /gw/admin/** 之后, 无凭证的环回探测只会拿到
        //    401/302, 那是"拦截器在工作"而不是"接口不存在" —— 不带凭证的探针会把自己变成假故障。
        Map<String, Object> surface = probeJson(req, UPSTREAM_PREFIX + "/meta/status");
        out.put("adminSurface", surface);
        // 探针 2: 网关计数器是否进了 Spring 的 MeterRegistry。实测 (2026-09-24, 9090 已流过真请求):
        // /actuator/metrics/zgw.request.total = 404 ⇒ MetricsGlobalFilter 记到的是
        // io.micrometer...Metrics.globalRegistry (静态复合), 与 actuator/MetricsController 注入的
        // registry 没打通。此字段 false 时, /gw/admin/metrics/summary 的 0 是"桥没通"不是"没有流量"。
        Map<String, Object> bridge = probeJson(req, "/actuator/metrics/zgw.request.total");
        Map<String, Object> metricsBridge = new LinkedHashMap<String, Object>();
        metricsBridge.put("expectedIfBridgeOk", Integer.valueOf(200));
        metricsBridge.put("actualStatus", bridge.get("status"));
        metricsBridge.put("bridgeVisible", Boolean.TRUE.equals(bridge.get("json"))
                && Integer.valueOf(200).equals(bridge.get("status")));
        out.put("micrometerBridge", metricsBridge);

        writeJson(resp, 200, out, mapper);
    }

    /**
     * GET /api/gw/** -> GET /gw/admin/** (同 JVM 环回)。
     * 例: /api/gw/routes -> /gw/admin/routes; /api/gw/routes/opc-api -> /gw/admin/routes/opc-api;
     *     /api/gw/metrics/summary -> /gw/admin/metrics/summary; /api/gw/meta/predicates -> /gw/admin/meta/predicates。
     */
    @GetMapping(PREFIX + "/**")
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String sub = upstreamPath(req.getRequestURI());
        if (sub == null) {
            writeJson(resp, 400, err(req.getRequestURI(), "bad path (whitelist [A-Za-z0-9_.-/], '..' 一律拒绝)"), mapper);
            return;
        }
        String query = req.getQueryString();
        if (query != null && !query.isEmpty() && !SAFE_QUERY.matcher(query).matches()) {
            writeJson(resp, 400, err(req.getRequestURI(), "bad query"), mapper);
            return;
        }
        String target = sub + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + req.getLocalPort() + target).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(LOOP_TIMEOUT_MS);
            conn.setReadTimeout(LOOP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            // 透传调用方凭证: 若将来把 /gw/** 也纳入鉴权 (或 z.config.auth.enabled=true),
            // 环回请求没有凭证就会被 302/401, 代理层不能假装数据是空的。
            String cookie = req.getHeader("Cookie");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            String auth = req.getHeader("Authorization");
            if (auth != null) conn.setRequestProperty("Authorization", auth);

            int status = conn.getResponseCode();
            InputStream src = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = src == null ? new byte[0] : readLimited(src, MAX_BODY_BYTES);
            String ct = conn.getContentType();
            boolean json = ct != null && ct.toLowerCase().indexOf("json") >= 0;
            // RouteAdminController#get 的 404 是 ResponseEntity.notFound().build() —— 空体、无 content-type。
            // 保留状态码原样透出, 但补一个 JSON 错误体, 否则前端只剩 axios 的 "status code 404" 一句话。
            if (body.length == 0 && status >= 400) {
                writeJson(resp, status, err(target, "upstream answered " + status + " with empty body"), mapper);
                return;
            }
            if (!json) {
                // 200+HTML = SPA fallback 兜住了不存在的 handler; 4xx+HTML = 容器错误页。
                // 两种都不许以"看起来有响应"的形态出去。
                writeJson(resp, 502, err(target, "upstream answered non-JSON (content-type=" + ct
                        + ", status=" + status + "). /gw/admin/** 未注册时会被 SPA fallback 接走, "
                        + "这不是网关没数据, 是 z-gw-admin 依赖没接通"), mapper);
                return;
            }
            resp.setStatus(status);
            resp.setContentType("application/json;charset=UTF-8");
            resp.setContentLength(body.length);
            resp.getOutputStream().write(body);
            resp.getOutputStream().flush();
        } catch (Exception e) {
            writeJson(resp, 502, err(target, "loopback call failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage()), mapper);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** "/api/gw/routes/opc-api" -> "/gw/admin/routes/opc-api"; 越界返回 null */
    private String upstreamPath(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) return null;
        String tail = uri.substring(PREFIX.length());
        String sub = UPSTREAM_PREFIX + (tail.isEmpty() ? "" : tail);
        if (sub.indexOf("..") >= 0 || !SAFE_PATH.matcher(sub).matches()) return null;
        return sub;
    }

    /** 环回 GET 一个 JSON 端点, 只取"状态码 + 是不是 JSON", 供 __instance 用; 失败不抛出 */
    private Map<String, Object> probeJson(HttpServletRequest req, String path) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + req.getLocalPort() + path).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(LOOP_TIMEOUT_MS);
            conn.setReadTimeout(LOOP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            String cookie = req.getHeader("Cookie");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            String auth = req.getHeader("Authorization");
            if (auth != null) conn.setRequestProperty("Authorization", auth);
            int status = conn.getResponseCode();
            String ct = conn.getContentType();
            r.put("path", path);
            r.put("status", Integer.valueOf(status));
            r.put("json", Boolean.valueOf(ct != null && ct.toLowerCase().indexOf("json") >= 0));
            // 302 = SsoInterceptor 拦下了 (说明凭证没透传到 or 已过期), 401 = 同上但按 JSON 协商应答。
            // 单独记一格, 免得页面把"拦了"读成"接口没了" —— 这两件事的处置动作完全相反。
            if (status == 302 || status == 401) r.put("rejectedByAuth", Boolean.TRUE);
            if (status >= 400) {
                byte[] b = conn.getErrorStream() == null
                        ? new byte[0] : readLimited(conn.getErrorStream(), 4096);
                if (b.length > 0) r.put("errorBody", new String(b, "UTF-8"));
            } else {
                byte[] b = conn.getInputStream() == null
                        ? new byte[0] : readLimited(conn.getInputStream(), 8192);
                if (b.length > 0) {
                    // 200+HTML (SPA fallback) 时 readTree 必炸 —— 解析失败不能把已填好的 status/json 冲掉
                    try {
                        r.put("body", new ObjectMapper().readTree(b));
                    } catch (Exception parse) {
                        r.put("rawBody", new String(b, "UTF-8").substring(0, Math.min(b.length, 200)));
                    }
                }
            }
        } catch (Exception e) {
            r.put("path", path);
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private boolean tcpConnectable(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignore) { }
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n, total = 0;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > max) throw new java.io.IOException("body exceeds " + max + " bytes");
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private Map<String, Object> err(String path, String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", "error");
        m.put("source", "z-opc-gw-proxy");
        m.put("message", msg);
        m.put("path", path);
        return m;
    }

    private void writeJson(HttpServletResponse resp, int status, Object body, ObjectMapper mapper) throws Exception {
        byte[] out = mapper.writeValueAsBytes(body);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(out.length);
        resp.getOutputStream().write(out);
        resp.getOutputStream().flush();
    }
}
