# 项目总览

## 1. 项目简介

z-gw 是组织内的**自研高性能 API 网关**：Netty 4 手写运行时（HTTP/1.1，**非响应式栈**），融合 Spring Cloud Gateway 的 Route + Predicate + Filter 模型、ShenYu 式 SPI 扩展与 APISIX 式路由匹配，一行依赖即可嵌入 Spring Boot 应用。

它在一人公司基座里的定位是流量入口：把「路由匹配 → 谓词判定 → 过滤器链（限流 / 熔断 / 重试 / CORS / 改写）→ Netty 出站代理」这条链路做成可嵌入、可 SPI 扩展、带管理 API 的网关内核。**不依赖 Spring Cloud Gateway / WebFlux 运行时，也不依赖任何外部注册中心才可启动。** z-gw 在组织内保持独立仓、自研自维护，不并入其他仓。

**技术栈：** Java 8 · Spring Boot 2.7.18（servlet 栈，无 WebFlux）· `io.netty:netty-all` 4.1.138.Final（Epoll 可用即启用）· Micrometer 1.12.13 + micrometer-registry-prometheus · slf4j-api 2.0.9 + log4j2 2.25.4 · snakeyaml 2.0 · `z-util-core` / `z-util-parser-json` · 管理面板 React 19 + antd 6 + Vite 6 + TypeScript 5.7

## 2. 模块架构

```
z-gw/
├── pom.xml                        # 根聚合 POM：z-gw:1.0.6，parent = z-boot-parent:1.1.0（relativePath 留空）
│                                  #   reactor 只有 4 个模块；z-gw-examples 已被注释排除
├── z-gw-api/                      # SPI 与模型（19 个类）
│                                  #   RouteDefinition / GatewayFilter(+Chain/Factory/Global) / FilterDefinition
│                                  #   / PredicateFactory(+Definition/Result) / RateLimiter / CircuitBreaker
│                                  #   / LoadBalancer / ServiceDiscovery+ServiceInstance / RouteLocator
│                                  #   / RouteWriter / GatewayContext / GatewayException / GatewayPhase
├── z-gw-core/                     # Netty 4 手写内核（47 个类 + 25 个测试类）
│   ├── server/                    #   GatewayServer（Nio/Epoll bootstrap）、GatewayHandler（FullHttpRequest 入口）
│   ├── router/                    #   RouteMatcher、InMemoryRouteRepository、FilterAssembler
│   ├── predicate/                 #   Path/Method/Header/Host/Weight/Time 六个工厂 + 注册表
│   ├── filter/                    #   4 个全局过滤器 + 8 个过滤器工厂 + 代理过滤器
│   │   ├── factory/               #     StripPrefix / PrefixPath / RewritePath / AddRequestHeader
│   │   │                          #     / AddResponseHeader / RequestRateLimiter / Hystrix / Retry
│   │   ├── global/                #     Tracing / Metrics / Logging / ErrorHandling
│   │   └── proxy/                 #     NettyProxyFilter
│   ├── ratelimit/                 #   TokenBucket / SlidingWindow / FixedWindow
│   ├── circuitbreaker/            #   SlidingWindowCircuitBreaker
│   ├── lb/                        #   Random / RoundRobin / Weighted / LeastConnections / IpHash
│   ├── http/                      #   BackendHttpClient（出站）
│   ├── service/                   #   LbUriResolver + discovery/StaticServiceDiscovery
│   ├── runtime/                   #   GatewayBootstrap（无 Spring 门面）、YamlRouteLoader、SimpleJsonRouteParser
│   └── config/                    #   GatewayProperties / ServerConfig / CorsPolicy
├── z-gw-spring-boot-starter/      # 7 个类：ZGatewayAutoConfiguration（绑定 zgw.*，ContextRefreshed 起 Netty、
│                                  #   PreDestroy 关停）+ ZGatewayProperties + ZGatewayHealthIndicator
│                                  #   + ZGatewayHealthAutoConfiguration + GwHostAutoConfiguration
│                                  #   + GwProxyController + ZGwRoutesConfig
├── z-gw-admin/                    # 4 个类：管理 REST（/gw/admin/**）+ Knife4j openapi3
│                                  #   RouteAdminController / GwMetricsController / MetaController
│                                  #   + ZGatewayAdminAutoConfiguration（组件扫描）
├── z-gw-admin-frontend/           # 管理面板（React 19 + antd 6 + Vite 6 + TS 5.7，private npm 工程，非 Maven 模块）
├── z-gw-examples/                 # 演示启动器（8888 web + 9090 网关双端口；已从 reactor <modules> 注释排除）
├── _doc/                          # 003_script（2 个脚本）+ 008_troubleshooting（1 篇踩坑记录）
├── _frontend/                     # z-gw-component + z-gw-suit（组织统一前端基座那套）
├── LICENSE                        # MIT
└── README.md
```

5 个 Maven 子模块 POM 均**未**设 `maven.deploy.skip`，也都在根 DM 里钉 `${project.version}`（防 fleet 表旧发布件把传递依赖拽回旧版），因此 `z-gw-admin` 同样会发 Maven Central —— 与 z-ctc 等仓「admin 不进 Central」的惯例不同，这是本仓现状。

## 3. 核心功能

### 3.1 路由与匹配（`z-gw-core`）

| 能力 | 实现 | 说明 |
|---|---|---|
| 路由仓库 + 热更新 | `InMemoryRouteRepository` + `RouteMatcher` | admin API 增删改后监听器自动 refresh；`POST /gw/admin/routes/reload` 整体重载 |
| URI scheme | `LbUriResolver` | 支持 `http://host:port` 直连、`lb://service` 经 SPI 解析、`forward://` 预留。**`https://` 显式拒绝**（出站客户端无 TLS，此前是静默降级成明文） |
| 谓词 | `PredicateFactoryRegistry` | 内置 **6 个**：Path / Method / Header / Host / Weight / Time，支持 Java `ServiceLoader` 扩展 |
| 灰度分流 | `WeightPredicateFactory` | Weight 分组按权重（examples 演示 90/10） |
| 负载均衡 | `Random` / `RoundRobin` / `Weighted` / `LeastConnections` / `IpHash` | `LoadBalancer` SPI，默认 RoundRobin |
| 路由加载 | `YamlRouteLoader` / `SimpleJsonRouteParser` | yml / JSON 双入口（无 Spring 场景可用） |

### 3.2 过滤器链

`FilterChainBootstrap.installDefaults` 的默认顺序是 **Tracing → Metrics → CORS → Logging → Error → NettyProxy**。

| 能力 | 实现 | 说明 |
|---|---|---|
| 限流 | `RateLimitFilterFactory`（`RequestRateLimiter`） | 算法实测三种：`tokenBucket` / `slidingWindow` / `fixedWindow`；`keyResolver: ip` 或 `header:<名称>` |
| 客户端 IP | `GatewayHandler.resolveClientIp` + `zgw.server.trustedProxyHops` | **默认 0 = 不采信任何请求头**，`clientIp` 只取 TCP 远端地址；设成 N（前面有 N 层自有代理）才从 `X-Forwarded-For` 右往左数第 N 段 |
| 熔断 | `HystrixFilterFactory` + `SlidingWindowCircuitBreaker` | errorThresholdPercentage / requestVolumeThreshold / sleepWindowMs 三参 |
| 重试 | `RetryFilterFactory` | retries + backoffMs |
| 改写 | `StripPrefix` / `PrefixPath` / `RewritePath` / `AddRequestHeader` / `AddResponseHeader` | 共 8 个过滤器工厂（另加限流、熔断、重试三个） |
| CORS | `CorsPolicy`（由 `ServerConfig` 的 `cors-*` 五个字段派生）+ `GatewayHandler` 的预检直通 | **默认关闭**；开启后 `corsAllowedOrigins` 支持 `*` 或白名单，命中回显并带 `Vary: Origin` |
| 出站代理 | `NettyProxyFilter` + `BackendHttpClient` | 独立 EventLoop 出站，`connectTimeoutMs` / `readTimeoutMs` / `writeTimeoutMs` 三档，透传 `X-Request-Id` |
| 可观测 | `MetricsGlobalFilter`（Micrometer：`zgw.request.duration` 百分位直方图 + `zgw.request.total`）+ `TracingGlobalFilter`（requestId + 耗时）+ `LoggingGlobalFilter` / `ErrorHandlingGlobalFilter` | Prometheus 走 `micrometer-registry-prometheus` |
| Actuator 健康 | `ZGatewayHealthIndicator` | `/actuator/health/zGateway` 上报 Netty server started 状态 |
| 9090 直通健康检查 | `GatewayHandler.isHealthPath` | `/health`、`/healthz`、`/` 返回 `{"status":"UP"}` |

### 3.3 管理 API（`z-gw-admin`）

| 路径 | 说明 |
|---|---|
| `GET/POST /gw/admin/routes` | 列出 / 创建路由 |
| `GET/PUT/DELETE /gw/admin/routes/{id}` | 单条路由查询 / 更新 / 删除 |
| `POST /gw/admin/routes/reload` | 整体重载路由表 |
| `GET /gw/admin/routes/stats` | 路由统计 |
| `GET /gw/admin/metrics/summary` | 聚合请求数 + 平均 RT（读 Micrometer registry） |
| `GET /gw/admin/metrics/routes` | 按路由维度汇总 |
| `GET /gw/admin/meta/status` | 网关运行时状态 |
| `GET /gw/admin/meta/predicates` | 内置谓词工厂列表 |
| `GET /gw/admin/meta/routes` | 全量路由（同 routes 列表） |
| `/actuator/health/zGateway`、`/actuator/prometheus` | 健康与指标（examples 暴露 health,info,metrics,prometheus） |
| `/doc.html` | Knife4j 接口文档 |

### 3.4 管理面板 `z-gw-admin-frontend/`

独立 npm 工程（`private: true`），`build` 脚本是 `tsc -b && vite build`。技术栈 React 19 + antd 6 + `react-router-dom` 7 + axios + dayjs + `@monaco-editor/react` + recharts，TypeScript 5.7。源码在 `src/`：`router.tsx`（路由）、`components/Layout.tsx`、`pages/Overview.tsx` / `pages/Metrics.tsx` / `pages/Meta.tsx` / `pages/Routes/List.tsx` / `pages/Routes/Edit.tsx`、`api/client.ts`。

### 3.5 演示启动器 `z-gw-examples/`

`z-gw-examples/src/main/resources/application.yml` 是一份完整的网关配置样例：`server.port: 8888`（Web 侧，承载 admin API / actuator / Knife4j）、`zgw.server.port: 9090`（Netty 网关侧）、`zgw.server.trustedProxyHops: 0`、`zgw.server.corsEnabled: false`、`zgw.security.jwtSecret: ${JWT_SECRET:changeit}`，以及 4 条示例路由：

1. `demo-echo` —— 静态 HTTP 转发到 `http://httpbin.org`，挂 `StripPrefix` + `AddResponseHeader`
2. `user-service-v1` / `user-service-v2-canary` —— 灰度路由，`lb://user-service` + `lb://user-service-v2`，`Weight` 分组 90 / 10
3. `api-limited` —— 限流路由，`RequestRateLimiter`（`replenishRate=10` / `burstCapacity=20` / `keyResolver=ip` / `algorithm=tokenBucket`）
4. `api-resilient` —— 熔断 + 重试，`Hystrix`（errorThresholdPercentage 50 / requestVolumeThreshold 20 / sleepWindowMs 5000）+ `Retry`（retries 3 / backoffMs 100）

`z-gw-examples` 已从根 `<modules>` 注释排除，是**非发布目标**的演示工程。

## 4. 对外集成 / 上下游

**上游依赖：** `z-boot-parent:1.1.0`（`relativePath` 留空）→ 第三方地板（Spring Boot 2.7.18、netty-all 4.1.138.Final、log4j2 2.25.4、snakeyaml 2.0）与兄弟仓 fleet（`z-util-core` / `z-util-parser-json`）。根 DM 里**刻意**压过地板的版本：Micrometer 1.12.13（地板 1.9.17）、slf4j-api 2.0.9（地板 1.7.36）、snakeyaml 2.0（压 CVE-2022-1471 口径）。根 DM 里另有两格**预留槽位**：`reactor-netty` 1.1.13 / `reactor-core` 3.6.10 —— 当前无任何模块声明依赖。

**对外边界：**

| 面 | 形态 | 端口 |
|---|---|---|
| 网关代理 | Netty `GatewayServer`（`zgw.server.port`） | **9090** |
| 管理面 / actuator / Knife4j | Spring MVC（`z-gw-admin` 的 Controller） | **8888**（examples 的 `server.port`） |
| 管理面板 | 独立静态站点，调 `/gw/admin/**` | 由前端工程自定 |

**对下游服务：** `http://host:port` 直连、`lb://service` 经 `ServiceDiscovery` SPI 解析、`forward://` 预留。**`https://` 上游被显式拒绝**（出站客户端无 TLS）。出站三档超时由 `zgw.server.connectTimeoutMs` / `readTimeoutMs` / `writeTimeoutMs` 控制。

**注册中心：** `ServiceDiscovery` 是 SPI，内置实现只有 `StaticServiceDiscovery`（代码 / yml 注册静态实例表）；**没有** z-config / nacos 客户端，也没有配置推送监听 —— `zgw.discovery.*` 这类配置键在 `ZGatewayProperties` 中不存在。`lb://` 路由需自备 `ServiceDiscovery` 实现才能解析。

**部署资产：仓内没有。** 本仓没有 Dockerfile / docker-compose / k8s 清单 / Makefile / `deploy/` 目录。仓外另有两处集成：`z-boot-integration-starters/z-boot-gw-starter`（聚合入口 `io.github.yuku123:z-boot-gw-starter`）与 `_frontend/z-gw-component` + `_frontend/z-gw-suit`（组织统一管理台那套）。

**对外发布：** Maven Central（根 POM 直接钉 `<version>1.0.6</version>`，flatten-maven-plugin 1.5.0 `oss` 模式常开；`-P central` 走 central-publishing-maven-plugin 0.8.0）。

## 5. 技术特点

1. **不是响应式栈，是自己写的 Netty 流水线**：全仓 `*.java` 实测 `Mono` / `Flux` 出现 **0 次**；运行时是 `GatewayServer` + `GatewayHandler` 的纯 Channel 流水线（Linux 可用时自动切 Epoll channel）。`reactor-netty` / `reactor-core` 只是根 DM 的预留槽位，没有任何模块声明依赖。
2. **"如实边界"是一节而不是一句免责**：README 用一整节列出「原 README 宣传过、代码里实际没有的东西」—— JWT 鉴权未生效（`zgw.security.*` 有配置 schema 但链里没有校验过滤器，全仓无 `Authorization` / `Bearer` 解析代码）、WebSocket 代理未实现（`ServerConfig.websocketEnabled` 字段存在但无人读取）、注册中心动态发现 / 推送未实现、HTTP/2（`h2c://`）/ IP 与 Header 黑白名单 / HMAC 请求体签名无实现、无仓内部署资产。
3. **CORS 刻意不进过滤器链**：预检在 `GatewayHandler.channelRead0` 被 `writeCorsOptions` 直接拦下返回（进不了链），实际响应的 ACAO 也由 `GatewayHandler` 在链外处理 —— 过滤器链拿不到「这是预检」这个上下文。
4. **客户端 IP 默认不采信任何请求头**：`zgw.server.trustedProxyHops` 缺省 **0**，`clientIp` 只取 TCP 远端地址。设成 N 才从 `X-Forwarded-For` 右往左数第 N 段。配置注释里明确写了滥用后果：设成 1 等于声明"只有内网代理能连到我"，端口对公网开放还开着它，任何客户端都能自己填 XFF 绕过 IP 限流。
5. **行为变更逐条记账**：README 用 11 条编号小节记「此前无效 / 此前是断链 / 此前是死参数」的修正 —— 灰度权重此前从未生效（金丝雀拿不到任何流量）、`AddResponseHeader` 此前是一条断链、限流 / 熔断 / 重试的 yml 简写参数此前一律无效、`cors-*` 五个字段此前全是死参数、加权轮询实例数变化此前会抛异常、熔断器的片边界此前跟着墙钟整除点走。
6. **SPI 扩展是 Java 原生 `ServiceLoader` + 工厂注册表**：谓词、过滤器工厂、负载均衡、服务发现四类都走同一套「接口 + 注册表 + 可选 ServiceLoader 发现」形状，扩展方不需要 Spring。
7. **启动不需要任何外部注册中心**：`GatewayBootstrap` 是无 Spring 门面，`YamlRouteLoader` / `SimpleJsonRouteParser` 提供 yml / JSON 双入口 —— 没有 Spring 的场景也能起网关。
8. **管理面与数据面是两个端口两个栈**：9090 是 Netty 自己的 Channel 流水线，8888 是 Spring MVC 承载 admin API / actuator / Knife4j；两者靠 `GwProxyController` 打通。
9. **根 DM 里有明确标注的"预留槽位"**：`reactor-netty` 1.1.13 / `reactor-core` 3.6.10 被写进 `<dependencyManagement>` 但无模块使用，README 在技术栈表里单列一行说明，避免后人误判本仓是响应式的。

## 6. 目录结构

源码包树共 6 段（`com.zifang.z.gw.*`），主源码 **79 个 `.java`**（api 19 + core 47 + starter 7 + admin 4 + examples 2），测试 26 个文件（core 25 + starter 1）：

```
z-gw/
├── z-gw-api/src/main/java/com/zifang/z/gw/api/
│   ├── RouteDefinition.java / RouteLocator.java / RouteWriter.java / FilterDefinition.java
│   ├── GatewayFilter.java / GatewayFilterChain.java / GatewayFilterFactory.java / GlobalFilter.java / GatewayPhase.java
│   ├── PredicateFactory.java / PredicateDefinition.java / PredicateResult.java
│   ├── RateLimiter.java / CircuitBreaker.java / LoadBalancer.java
│   ├── ServiceDiscovery.java / ServiceInstance.java
│   └── GatewayContext.java / GatewayException.java
├── z-gw-core/src/main/java/com/zifang/z/gw/core/
│   ├── server/       (2)  # GatewayServer（Nio/Epoll bootstrap）、GatewayHandler
│   ├── router/       (3)  # RouteMatcher、InMemoryRouteRepository、FilterAssembler
│   ├── predicate/    (7)  # Path/Method/Header/Host/Weight/Time 六个工厂 + PredicateFactoryRegistry
│   ├── filter/           # 3 个直属：FilterChainBootstrap / DefaultGatewayFilterChain / GatewayFilterFactoryRegistry
│   │   ├── factory/  (9)  # StripPrefix / PrefixPath / RewritePath / AddRequestHeader / AddResponseHeader
│   │   │                 #   / RequestRateLimiter / Hystrix / Retry + ShorthandArgs（yml 简写参数解析）
│   │   ├── global/   (4)  # Tracing / Metrics / Logging / ErrorHandling
│   │   └── proxy/    (1)  # NettyProxyFilter
│   ├── ratelimit/    (3)  # TokenBucket / SlidingWindow / FixedWindow
│   ├── circuitbreaker/(1) # SlidingWindowCircuitBreaker
│   ├── lb/           (5)  # Random / RoundRobin / Weighted / LeastConnections / IpHash
│   ├── http/         (1)  # BackendHttpClient
│   ├── service/      (1)  # LbUriResolver
│   │   └── discovery/ (1) # StaticServiceDiscovery
│   ├── runtime/      (3)  # GatewayBootstrap、YamlRouteLoader、SimpleJsonRouteParser
│   └── config/       (3)  # GatewayProperties、ServerConfig、CorsPolicy
├── z-gw-spring-boot-starter/src/main/java/com/zifang/z/gw/starter/
│   ├── ZGatewayAutoConfiguration.java / ZGatewayProperties.java / ZGwRoutesConfig.java
│   ├── health/  (2)  # ZGatewayHealthAutoConfiguration / ZGatewayHealthIndicator
│   └── host/    (2)  # GwHostAutoConfiguration / GwProxyController
├── z-gw-admin/src/main/java/com/zifang/z/gw/admin/
│   ├── controller/   (3)  # RouteAdminController / GwMetricsController / MetaController
│   └── ZGatewayAdminAutoConfiguration.java
└── z-gw-examples/src/main/java/com/zifang/z/gw/examples/
    └── GwExamplesApplication.java / config/ZGatewayExamplesPatchConfig.java
```

`_doc/` 侧两个桶：`003_script/`（`deploy_maven_center.sh` / `verify_central.sh`）与 `008_troubleshooting/`（`01_z-gw控制台_dev与build双双不可用.md` —— 记录 `src/pages/Routes/List.tsx` 的 import 少一层导致 vite dev 五条路由全白屏、`vite build` rc=1 的实测账本）。

`z-gw-admin-frontend/` 是独立的 TypeScript + React 工程（`src/router.tsx` / `src/components/Layout.tsx` / `src/pages/**` / `src/api/client.ts` / `src/styles/global.css`），不在 Maven reactor 内。
