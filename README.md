# z-gw

> 自研高性能 API 网关 —— Netty 4 手写运行时（HTTP/1.1 · **非响应式栈**），融合 Spring Cloud Gateway 的
> Route + Predicate + Filter 模型、ShenYu 式 SPI 扩展与 APISIX 式路由匹配，一行依赖即可嵌入 Spring Boot 应用。

一人公司基座的流量入口：把"路由匹配 → 谓词判定 → 过滤器链（限流/熔断/重试/CORS/改写）→ Netty 出站代理"
这条链路做成可嵌入、可 SPI 扩展、带管理 API 的网关内核，不依赖 Spring Cloud Gateway / WebFlux 运行时，
也不依赖任何外部注册中心才可启动。z-gw 在组织内保持**独立仓库、自研自维护**，不并入其他仓。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-gw`（remote：`github.com/z-opc-foundation/z-gw`） |
| **Maven 坐标** | `io.github.yuku123:z-gw:1.0.5`（根 POM 直接钉 `<version>`，flatten-maven-plugin 1.5.0 `oss` 模式常开） |
| **当前版本** | `1.0.5`（2026-09 由 1.0.4 抬级，随 z-boot-dependencies 1.0.19 批量发布） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘） |
| **Maven Central** | 已发布（repo1 实测 HTTP 200）：`z-gw` / `z-gw-api` / `z-gw-core` / `z-gw-spring-boot-starter` / `z-gw-admin` 均到 `1.0.5` |
| **默认端口** | `9090` — Netty 网关代理端口（`zgw.server.port`）；`8888` — Spring Web 管理端口（examples 的 `server.port`，承载 admin API / actuator / Knife4j） |
| **运行口径** | Java 8 · Spring Boot 2.7.18（servlet 栈；版本经 `z-boot-parent` → `z-boot-dependencies` 地板下发） |
| **reactor 模块** | 4 个：`z-gw-api`、`z-gw-core`、`z-gw-spring-boot-starter`、`z-gw-admin`（`z-gw-examples` 已临时移出 `<modules>`，非发布目标） |
| **最近更新** | 2026-09-30 |

近期真实改造（`git log` 实测，README 上次落笔后已 16 个提交）：parent 从悬空的 `com.zifang:z-opc:1.0.0-SNAPSHOT`
统一换到 `z-boot-parent:1.0.21`；netty 收敛为地板单一口径 4.1.138.Final（消除同 reactor 双版本）；
`flatten` 从 `central` profile 提到根 build 常开（修掉"本地 install 出的 pom 仍带悬空 parent"）；
Java 8 兼容修复（admin 的 4 处 `Map.of`、`YamlRouteLoader.readAllBytes`）。

---

## 🎯 能力清单（逐条对应代码）

路由与匹配（`z-gw-core`）：

| 能力 | 实现 | 说明 |
|------|------|------|
| 路由仓库 + 热更新 | `InMemoryRouteRepository` + `RouteMatcher` | admin API 增删改后监听器自动 refresh；`POST /gw/admin/routes/reload` 整体重载 |
| URI scheme | `LbUriResolver` | 实测支持 `http(s)://host:port` 直连、`lb://service` 经 SPI 解析、`forward://` 预留 |
| 谓词 | `PredicateFactoryRegistry` | 内置 6 个：Path / Method / Header / Host / Weight / Time，支持 Java `ServiceLoader` 扩展 |
| 灰度分流 | `WeightPredicateFactory` | Weight 分组按权重（examples 演示 90/10） |
| 负载均衡 | `Random` / `RoundRobin` / `Weighted` / `LeastConnections` / `IpHash` | `LoadBalancer` SPI，默认 RoundRobin |
| 路由加载 | `YamlRouteLoader` / `SimpleJsonRouteParser` | yml / JSON 双入口（无 Spring 场景可用） |

过滤器链（`FilterChainBootstrap.installDefaults`，顺序 Tracing → Metrics → CORS → Logging → Error → NettyProxy）：

| 能力 | 实现 | 说明 |
|------|------|------|
| 限流 | `RateLimitFilterFactory`（`RequestRateLimiter`） | 算法实测三种：`tokenBucket` / `slidingWindow` / `fixedWindow`；`keyResolver: ip` 或 `header:<名称>` |
| 熔断 | `HystrixFilterFactory` + `SlidingWindowCircuitBreaker` | errorThresholdPercentage / requestVolumeThreshold / sleepWindowMs 三参 |
| 重试 | `RetryFilterFactory` | retries + backoffMs |
| 改写 | `StripPrefix` / `PrefixPath` / `RewritePath` / `AddRequestHeader` / `AddResponseHeader` | 均为过滤器工厂 |
| CORS | `CorsGlobalFilter` + `GatewayHandler` 的 OPTIONS 预检直通 | `zgw.server.cors-*` 参数 |
| 出站代理 | `NettyProxyFilter` + `BackendHttpClient` | 独立 EventLoop 出站，`connectTimeoutMs` / `readTimeoutMs` / `writeTimeoutMs` 三档，透传 `X-Request-Id` |
| 可观测 | `MetricsGlobalFilter`（Micrometer：`zgw.request.duration` 百分位直方图 + `zgw.request.total`）+ `TracingGlobalFilter`（requestId + 耗时）+ `LoggingGlobalFilter` / `ErrorHandlingGlobalFilter` | Prometheus 走 `micrometer-registry-prometheus` |
| Actuator 健康 | `ZGatewayHealthIndicator` | `/actuator/health/zGateway` 上报 Netty server started 状态 |
| 管理 API | `z-gw-admin` 三个 Controller | 路由 CRUD / reload / stats、指标 summary / routes、meta status / predicates |
| 管理面板 | `z-gw-admin-frontend/`（React 19 + antd 6 + Vite） | 独立 npm 工程，调 `/gw/admin/**` |
| 9090 直通健康检查 | `GatewayHandler.isHealthPath` | `/health`、`/healthz`、`/` 返回 `{"status":"UP"}` |

### 如实边界（原 README 宣传过、代码里实际没有的东西）

- **不是响应式/WebFlux 网关**：全仓 `*.java` 实测 `Mono`/`Flux` 出现 0 次；`reactor-netty:1.1.13`、
  `reactor-core:3.6.10` 只是根 POM `<dependencyManagement>` 预留槽位（无任何模块声明依赖），运行时是
  纯 Netty Channel 流水线（`GatewayServer` + `GatewayHandler`，Linux 可用时自动切 Epoll channel）。
- **JWT 鉴权未生效**：`zgw.security.*`（jwtSecret / publicPaths / jwtLeewaySeconds）有配置 schema，
  但内置过滤器链里没有 JWT 校验过滤器，全仓也无 `Authorization`/`Bearer` 解析代码 —— 当前是预留位，勿当安全能力用。
- **WebSocket 代理未实现**：`ServerConfig.websocketEnabled` 字段存在但无人读取，Netty pipeline
  （HttpServerCodec + HttpObjectAggregator + ExpectContinue）里没有 WebSocket 协议处理器。
- **注册中心动态发现/推送未实现**：`ServiceDiscovery` 是 SPI，内置实现只有 `StaticServiceDiscovery`
  （代码/yml 注册静态实例表）；没有 z-config / nacos 客户端，也没有配置推送监听，`zgw.discovery.*`
  这类配置键在 `ZGatewayProperties` 中不存在。`lb://` 路由需自备 `ServiceDiscovery` 实现才能解析。
- **HTTP/2（`h2c://`）、IP/Header 黑白名单、HMAC 请求体签名**：代码中无实现（`BackendHttpClient`
  注释自述"HTTP/2 后续升级"）。
- **无仓内部署资产**：本仓没有 Dockerfile / docker-compose / k8s 清单 / Makefile / `deploy/` 目录，
  原 README 贴的 Dockerfile 与 k3s YAML 均非仓内文件。

---

## 🏗️ 项目结构

```
z-gw/
├── pom.xml                        # 根聚合 POM：parent z-boot-parent:1.0.21，版本 1.0.5，central profile 发布
├── z-gw-api/                      # SPI 与模型（19 个类）：RouteDefinition / GatewayFilter(+Chain/Factory/Global)
│                                  #   / PredicateFactory / RateLimiter / CircuitBreaker / LoadBalancer
│                                  #   / ServiceDiscovery+ServiceInstance / GatewayContext；依赖 z-util-core、z-util-parser-json
├── z-gw-core/                     # Netty 4 手写内核（46 个类 + 5 个测试类）：
│   ├── server/                    #   GatewayServer（Nio/Epoll bootstrap）、GatewayHandler（FullHttpRequest 入口）
│   ├── router/                    #   RouteMatcher、InMemoryRouteRepository、FilterAssembler
│   ├── predicate/                 #   Path/Method/Header/Host/Weight/Time + 注册表
│   ├── filter/                    #   全局过滤器、8 个工厂、NettyProxyFilter
│   ├── ratelimit/                 #   TokenBucket / SlidingWindow / FixedWindow
│   ├── circuitbreaker/            #   SlidingWindowCircuitBreaker
│   ├── lb/                        #   5 种负载均衡
│   ├── http/                      #   BackendHttpClient（出站）
│   ├── service/                   #   LbUriResolver + discovery/StaticServiceDiscovery
│   ├── runtime/                   #   GatewayBootstrap（无 Spring 门面）、YamlRouteLoader、SimpleJsonRouteParser
│   └── config/                    #   GatewayProperties / ServerConfig
├── z-gw-spring-boot-starter/      # ZGatewayAutoConfiguration（绑定 zgw.*，ContextRefreshed 起 Netty、PreDestroy 关停）
│                                  #   + ZGatewayHealthIndicator；AutoConfiguration.imports 注册
├── z-gw-admin/                    # 管理 REST（/gw/admin/**）+ Knife4j openapi3；ZGatewayAdminAutoConfiguration 组件扫描
├── z-gw-admin-frontend/           # 管理面板（React 19 + antd 6 + Vite 6 + TS 5.7，npm 工程，非 Maven 模块）
├── z-gw-examples/                 # 演示启动器（当前 8888 web + 9090 网关双端口；已从 reactor `<modules>` 注释排除）
├── _doc/                          # 文档收口目录，见文末「文档目录」
├── LICENSE                        # MIT
└── README.md
```

5 个 Maven 子模块 POM 均**未**设 `maven.deploy.skip`，也都在根 DM 里钉 `${project.version}`
（防 fleet 表旧发布件把传递依赖拽回 1.0.4），因此 `z-gw-admin` 同样会发 Maven Central —— 与 z-ctc 等仓
"admin 不进 Central"的惯例不同，这是本仓现状。

---

## 🔧 技术栈（pom 实测）

| 层级 | 技术 |
|------|------|
| 语言 / 运行时 | Java 8（compiler source/target 8；class major 52） |
| 框架 | Spring Boot 2.7.18（由父链 `z-boot-dependencies` 地板下发；servlet 栈，无 WebFlux） |
| 网络层 | `io.netty:netty-all` 4.1.138.Final（地板 netty-bom 下发；Epoll 可用即启用） |
| 指标 | Micrometer 1.12.13 + micrometer-registry-prometheus（根 DM 压过地板的 1.9.17） |
| 日志 | slf4j-api 2.0.9（根 DM 刻意抬过地板 1.7.36）+ log4j2 2.25.4（`log4j-slf4j2-impl` 由 core 字面承载） |
| YAML | snakeyaml 2.0（根 DM 压 CVE-2022-1471 口径） |
| 工具 | `z-util-core` / `z-util-parser-json`（版本走 z-boot-fleet 权威表） |
| 接口文档 | knife4j-openapi3-spring-boot-starter（admin） |
| 测试 | JUnit Jupiter 5.10.2 + Mockito 5.12.0；surefire 3.2.5（本仓 pluginManagement 保留旧尺） |
| 前端面板 | React 19 · antd 6 · Vite 6 · TypeScript 5.7（`z-gw-admin-frontend`，private npm 工程） |
| 构建/发布 | Maven；flatten-maven-plugin 1.5.0（oss 模式常开）；`-P central` 走 central-publishing-maven-plugin 0.8.0 |
| 预留槽位 | reactor-netty 1.1.13 / reactor-core 3.6.10 —— 仅根 DM 槽位，当前无代码使用 |

---

## 🚀 快速开始

### 编译安装（reactor 4 模块）

```bash
mvn clean install -DskipTests
```

构建需能解析 `io.github.yuku123:z-boot-parent:1.0.21`（repo1 或镜像）；第三方版本一律走父链，
模块 POM 不应再有字面版本钉（`log4j-slf4j2-impl` 例外，见根 POM 注释）。

### 跑通演示（examples，含 admin API）

```bash
cd z-gw-examples && mvn spring-boot:run
```

`z-gw-examples` 不在根 reactor 里，需先完成上一步 install。起两个端口：
`http://localhost:8888`（Spring Web：`/gw/admin/**`、`/actuator/**`、`/doc.html`）与
`http://localhost:9090`（Netty 网关：`/health`、`/demo/**` 静态转发演示）。
完整配置见 [`z-gw-examples/src/main/resources/application.yml`](z-gw-examples/src/main/resources/application.yml)。

### 嵌入自己的 Spring Boot 应用

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-gw-spring-boot-starter</artifactId>
    <version>1.0.5</version>
</dependency>
```

```yaml
zgw:
  enabled: true
  server:
    port: 9090
  routes:
    - id: user-service
      uri: http://backend-host:8080   # lb:// 需自备 ServiceDiscovery SPI 实现
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
```

谓词/过滤器参数为 `name + args` 原生 map 形态（`_genkey_0` 即 Spring Binder 对无键列表项的约定），
与 Spring Cloud Gateway 的字符串 SpEL 简写**不通用**。`zgw.security.jwt-secret` 等敏感项必须经
环境变量/配置中心注入，禁止把真实值提交进 yml。

---

## 🔌 API 一览

网关端口 `9090`（Netty）：`/health`、`/healthz`、`/` 直通 200；其余路径按路由表匹配，未命中返回 JSON 404。

管理端口（examples 为 `8888`，由 `z-gw-admin` Controller 提供）：

| 路径 | 说明 |
|------|------|
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

---

## 🧪 测试

```bash
mvn test
```

实测规模：`z-gw-core` 5 个测试类、25 个 `@Test`（RouteMatcher 6 / PredicateFactory 7 /
CircuitBreaker 4 / RateLimiter 4 / LoadBalancer 4），无外部依赖即可全跑。
旧 README 的"214 单元 + 45 集成 + 8 SpringBoot + 36 回归"为模板遗留数字，非本仓实测。

---

## 📦 发布

无仓内 Dockerfile / compose / k8s 清单。Maven Central 发布走根 POM `central` profile
（`mvn deploy -P central`）；发布脚本要求凭证全部从仓库根 `.env`（已被 `.gitignore` 排除）注入，
键名为 `CENTRAL_USERNAME` / `CENTRAL_TOKEN` / `CENTRAL_GPG_PASSPHRASE`，
详见 [`_doc/003_script/deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh)。

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)（版权行 "Copyright (c) 2026 z-opc-foundation"）。

---

## 🔗 相关项目

| 项目 | 关系 |
|---|---|
| [z-boot](https://github.com/z-opc-foundation/z-boot) | 父链：`z-boot-parent` / `z-boot-dependencies` 版本地板 |
| [z-cache](https://github.com/z-opc-foundation/z-cache) · [z-mq](https://github.com/z-opc-foundation/z-mq) · [z-rpc](https://github.com/z-opc-foundation/z-rpc) · [z-vector](https://github.com/z-opc-foundation/z-vector) | 同组织兄弟仓 |

聚合入口 `io.github.yuku123:z-boot-gw-starter`（repo1 实测 1.0.5 可拉取）可一行引入 z-gw 并锁定版本。

_Maintained by the z-opc-foundation organization. z-gw 保持独立仓演进。_

---

## 文档目录

本项目文档统一收口在 `_doc/` 下:

- `_doc/001_arch/` — 架构文档（目前为空目录，暂无文件）
- `_doc/002_deploy/` — 部署资料（目前为空目录，暂无文件）
- [`_doc/003_script/`](_doc/003_script/) — 运维脚本:
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh) — Maven Central 发布脚本
- `_doc/004_skill/` — AI skill 定义（目前为空目录，暂无 skill）

各文档详细说明见各子目录。
