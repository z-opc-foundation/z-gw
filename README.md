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
| URI scheme | `LbUriResolver` | 支持 `http://host:port` 直连、`lb://service` 经 SPI 解析、`forward://` 预留。**`https://` 显式拒绝**（出站客户端无 TLS，此前是静默降级成明文，见下方「已知限制」） |
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

## ⚠️ 已知限制与行为变更

这一节记的是**代码当前真实做不到的事**，以及与旧版本不同的行为。

### 1. `https://` 上游：显式拒绝，不再静默降级（行为变更）

`BackendHttpClient` 的 pipeline 里**没有 `SslHandler`**，出站只支持明文 HTTP。
在此之前，配了 `https://` 的路由会**静默降级成明文**——`LbUriResolver` 走静态分支时
默认端口取 80，出站仍用 `HttpClientCodec`。结果是"以为配了 https 就安全了"，
实际把 `Authorization` / `Cookie` 明文发到网络上。

现在改为**启动即报错**（`IllegalStateException`），错误消息说明原因与两条出路：

- 改用 `http://` 上游；
- 或先为出站客户端补 TLS 支持。

**升级影响**：配了 `https://` 上游的部署在升级后会启动失败。这是有意为之——
继续静默降级等于继续泄露凭证。升级前请先全量检查路由配置里的 `uri:`。

### 2. 入站读超时固定 60 秒，不跟随任何配置

`GatewayServer` 的 `ReadTimeoutHandler(60, SECONDS)` 是硬编码的，**不读 `ServerConfig`
任何配置项**。它与出站 `readTimeoutMs` 是两个互不相干的上限：后者管"网关等后端"，
前者管"客户端多久不吐数据算断连"。要调入站超时目前只能改代码。

其余"配置项存在但无人读取"的情况（`websocketEnabled`、JWT 鉴权、注册中心发现等）
见上文 [如实边界](#如实边界原-readme-宣传过代码里实际没有的东西)。

### 3. 最少连接：在飞计数改由代理过滤器维护（行为变更）

`LeastConnectionsLoadBalancer` 此前**恒定返回实例列表的第一个**：`ServiceInstance` 上的
`activeConnections` 只有 getter/setter，全仓没有任何生产代码调用过
`incrementActiveConnections()`，所有实例计数恒为 0，而 `select` 里的 `conns < min`
在第一个实例就命中、后续全被 `0 < 0` 挡掉。

现在 `NettyProxyFilter` 在选实例后 `increment`、在转发结束的 `finally` 里 `decrement`
（正常写回 / 后端失败 / 超时 / 线程中断四条出口都归还）。

**升级影响**：配了 `leastConnections` 的服务流量分布会变——从"全打第一个实例"变成
真正按在飞连接数分配。若此前靠这个"只打一个实例"的行为做了容量假设，需要重新评估。

### 4. 加权轮询：实例数变化不再抛异常（行为变更）

`WeightedLoadBalancer` 的 SWRR 状态 `currents` 数组在**首次调用**时按当时的实例数定长。
服务发现新增实例（扩容、滚动发布）后数组越界，`ArrayIndexOutOfBoundsException` 从 `select`
抛出、被 `NettyProxyFilter` 包成 `BadGatewayException`——**一次扩容会把整条 `lb://` 路由
打成 502，直到网关重启才恢复**。

现在每次选择前按当前实例数扩容（缩容方向保留旧值，SWRR 的 `currents` 本身有界，会自行拉回）。

### 5. 灰度权重此前从未生效：金丝雀拿不到任何流量（行为变更）

yml 简写 `Weight=user_group,90` 经 `PredicateDefinition.of(name, singleArg)` 收成
`{"_genkey_0": "user_group,90"}` —— key 是定长占位符，两个值都在 value 里。
`WeightPredicateFactory` 却把 key 当 group、拿整串 value 做 `parseInt`：

- group 恒为 `_genkey_0`，所有灰度组塌成一组；
- weight 遇 `NumberFormatException` 被吞成 0 → `selectByWeight` 的 `total <= 0`
  → 恒取组内第一条 → **金丝雀 0%**。

`YamlRouteLoader.defaultRoutes()` 自带的演示灰度（v1 90 / canary 10）就是这样一条
拿不到流量的路由。现在两种写法都解析：简写取 value 逗号左右两边，map 写法照旧
（key 即 group）。`HeaderPredicateFactory` 同病，`Header=X-Trace-Id,.+` 会去查
`req.header._genkey_0` 从而永不命中，一并修好——同仓的
`AddRequestHeaderFilterFactory` / `AddResponseHeaderFilterFactory` 早就按
`_genkey_0` 约定解析了，这次是让谓词侧与过滤器侧对齐。

同时收紧了权重选择的取值范围：配置不约束同组路由的其余谓词相同，而
`groupedByWeight` 收的是「所有带 Weight 谓词的路由」。若整组拿去加权，组里有一条
`Path=/b/**` 时，`/a` 的请求会按权重比例被送到 `/b` 去。现在只让真正匹配本请求的
组成员参与分配。

**升级影响**：配了灰度的部署在升级后会真的开始分流。升级前请确认同组路由的
非 Weight 谓词一致（否则原先"看起来是 100%/0%"的隐性配置会突然真的按比例走）。

### 6. `AddResponseHeader` 此前是一条断链（行为变更）

`AddResponseHeader` 过滤器把头写进 ctx 的 `resp.headers` attribute，而**全仓没有
任何一处读它**。过滤器自己的 Javadoc 写着「由 `GatewayHandler.writeFullResponse`
时合并」，但那个方法三个参数里压根没有 ctx，拿不到这个 attribute。

工厂解析是对的（它按 `_genkey_0` 拆出了 `X-Gateway` 和 `z-gw-demo`），过滤器也
真的在 `DefaultGatewayFilterChain` 里跑了（order=800，先于 order=999 的代理过滤器），
只是结果被丢在半路。后果：`YamlRouteLoader.defaultRoutes()` 的演示路由明写
`AddResponseHeader=X-Gateway, z-gw-demo`，实际响应里从来没有这个头。
对称方向的 `AddRequestHeader` 是通的（`BackendHttpClient` 用 `target.set(...)`
合并 `req.headers`），只有响应方向断了。

现在 `writeFullResponse` 增加了带 `extraHeaders` 的重载，`NettyProxyFilter` 把
`ctx` 里的 `resp.headers` 传进去；三参重载保留并委托给它，既有调用方不受影响。

**优先级**：显式过滤器 > 后端（与请求方向 `target.set(...)` 一致）。因此
`AddResponseHeader=Access-Control-Allow-Origin, https://app.example.com` 现在能
把网关给的 `*` 兜底换成具体来源——兜底那段 `contains` 判断是为了尊重后端自己
显式给出的 CORS 策略，不该把网关自己的路由级配置也挡在外面。

**升级影响**：此前配了 `AddResponseHeader` 却没看到头的部署，升级后这些头会真的
出现在响应上。若某个头与后端同名，会以网关配置的值为准。

### 7. 限流 / 熔断 / 重试的 yml 简写参数此前一律无效（行为变更）

`RequestRateLimiter` / `Hystrix` / `Retry` 三个工厂按**语义 key** 取参数
（`args.get("replenishRate")` 等），而 yml 简写
`RequestRateLimiter=replenishRate=10,burstCapacity=20` 经
`FilterDefinition.of(name, singleArg)` 收成 `{"_genkey_0": "replenishRate=10,burstCapacity=20"}`
—— key 是定长占位符，配置项名和值都挤在 value 里。于是**每个参数都取不到、
静默落回默认值**，日志里没有任何异常：

| 配置 | 实际生效 |
|------|----------|
| `replenishRate=1,burstCapacity=2` | `10 / 20`（宽 5~10 倍） |
| `Hystrix=requestVolumeThreshold=1,errorThresholdPercentage=1` | `20 / 50` |
| `Retry=retries=5` | `3` |

限流那条最要命：把限流配紧以保护后端，实际生效的却是宽 5~10 倍的默认值。
三个工厂现在都先经 `ShorthandArgs.normalize` 把简写按 `k=v,k=v` 拆开再取值，
map 写法不变。

**升级影响**：配了限流/熔断/重试简写的部署在升级后参数会真的生效。
升级前请确认那些数字是你想要的——尤其是限流，升级后流量会被真正拦住。

### 8. 未修改但已知的契约缺口：`RewritePath` 的简写语法不可用

`RewritePathFilterFactory` 要求 `args.size() >= 2`，而简写
`RewritePath=/red(?<segment>.*), /${segment}` 只会产生 1 个 arg，必然抛
`IllegalArgumentException`；该异常被 `FilterAssembler.buildFilter` 捕获后
`log.error` + 返回 null，**过滤器被静默丢弃**，请求带着未改写的 path 打到后端。

该类 Javadoc 第 15 行推荐的正是这个语法，但全工作区 grep 显示除 Javadoc/README 外
**没有任何 yml 或测试真的用过它**。没有定为缺陷、也不擅自修，原因是拆分规则本身
有歧义：正则里可以出现逗号（`/a{1,2}/b`），`regex,replacement` 按第一个逗号拆会切错、
按最后一个拆则会在替换串含逗号时切错，无法从现有材料判定哪种是本意。
需要先定规则（或者干脆规定含逗号的正则必须用 map 写法）再改。

---

## 🧪 测试

```bash
mvn test
```

实测规模：**153 个 `@Test`**（`z-gw-core` 150 + `z-gw-spring-boot-starter` 3），
24 个测试类，无外部依赖即可全跑：

| 测试类 | 数 | 覆盖 |
|--------|----|------|
| `RateLimiterClockInjectionTest` | 11 | 两个限流器的时钟可注入、参数校验、429 带 `Retry-After` |
| `YamlRouteLoaderStrictnessTest` | 9 | 拼错的谓词 / 过滤器不再被静默丢弃 |
| `SlidingWindowCircuitBreakerWindowTest` | 9 | 滑动窗口真的会滑（老失败滑出后不再压失败率）、构造期 fail-fast |
| `RouteMatcherWeightTest` | 9 | 灰度权重真按比例分流，且不把流量发给不匹配的路由 |
| `GatewayHandlerNotFoundJsonTest` | 8 | 404 响应体是合法 JSON，path / `X-Request-Id` 不能注入字段 |
| `GatewayHandlerCorsTest` | 7 | 预检与实际响应都要带 `ACAO`（此前只有预检有，跨域全被浏览器拦掉） |
| `GatewayHandlerErrorResponseTest` | 7 | 过滤器抛异常必须真的写出 HTTP 响应、同一请求不写两个响应 |
| `RateLimiterKeyCardinalityTest` | 7 | 三个限流器的 keyed 状态表有界（持续轮换 key 不能撑爆堆） |
| `PredicateFactoryTest` | 7 | 6 个内置谓词 + SPI 扩展 |
| `PredicateShorthandArgTest` | 7 | 谓词的 yml 简写 `Header=X-Trace-Id,.+` / `Weight=group,90` 真的被解析 |
| `LbUriResolverSchemeTest` | 7 | scheme 分支，含 `https://` 显式拒绝 |
| `RouteMatcherTest` | 6 | 路由匹配与热更新 |
| `RateLimiterTest` | 6 | 三个限流器基本语义 |
| `BackendHttpClientHeaderTest` | 6 | 出站只转发端到端头（hop-by-hop 剥除） |
| `GatewayHandlerOffloadTest` | 5 | 过滤器链不占 Netty EventLoop、池满回错误响应 |
| `FilterFactoryShorthandArgTest` | 8 | 限流/熔断/重试三个工厂的 yml 简写参数真的生效（此前静默落回默认值） |
| `AddResponseHeaderEndToEndTest` | 7 | `AddResponseHeader` 声明的头真的落到出站响应，并定义与后端/ACAO 兜底的优先级 |
| `LeastConnectionsInFlightTest` | 5 | 最少连接的输入真有人维护（转发中在计数、四条出口都归还） |
| `WeightedLoadBalancerScaleTest` | 4 | SWRR 状态跟着实例数走（扩容不再把整条路由打成 502） |
| `SlidingWindowCircuitBreakerHalfOpenLeakTest` | 4 | 半开期在飞名额无条件归还，后端恢复后熔断器能闭合 |
| `LoadBalancerTest` | 4 | 4 种负载均衡 |
| `CircuitBreakerTest` | 4 | 熔断器状态机基本流转 |
| `NettyProxyFilterTimeoutTest` | 3 | 后端等待上限跟随 `readTimeoutMs` |
| `ZGatewayAutoConfigurationRefreshTest` | 3 | 重复 `ContextRefreshedEvent` 不会累积 listener |

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
