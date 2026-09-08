# z-gw — 自研高性能 API 网关

> 融合 Spring Cloud Gateway / Apache ShenYu / APISIX / Envoy 设计，基于 **Netty 异步非阻塞运行时 + Spring Boot 自动装配** 的工业级 API 网关。

![version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-blue) ![spring-boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-green) ![netty](https://img.shields.io/badge/Netty-4.1.100-purple) ![java](https://img.shields.io/badge/Java-8-orange) ![tests](https://img.shields.io/badge/tests-25%20passing-brightgreen)

---

## 一句话定位

z-gw 是一个 **Java 语言、面向 Spring Boot 生态、纯 Netty 非阻塞运行时、SPI 可扩展** 的高性能 API 网关，对标 Spring Cloud Gateway / Apache APISIX / Envoy 的核心能力（路由 + 谓词 + 过滤器链 + 负载均衡 + 限流熔断 + 灰度发布 + 链路追踪 + 指标采集 + 管理 API + 前端面板），但通过"Netty 直连 + Spring Boot 一行集成"简化部署，不引入 WebFlux 响应式编程的心智负担。

---

## 为什么需要 z-gw

| 痛点 | z-gw 的解法 |
|------|------------|
| Spring Cloud Gateway 依赖 WebFlux，心智负担重 | 纯 Netty 同步风格，filter chain 无响应式 |
| APISIX 依赖 Nginx/Lua，部署复杂 | Java 单进程部署，Spring Boot 一行依赖启动 |
| 需要前端管理面板但开源网关大多没有 | 内置 React 19 + Vite 6 + Ant Design 6 管理面板 |
| 开源网关 SPI 不够灵活 | 全 SPI 化：PredicateFactory / FilterFactory / LoadBalancer / RateLimiter / CircuitBreaker / ServiceDiscovery / RouteLocator |
| 小型团队需要自研可控 | Java 单语言 + z-opc 统一技术栈 + 自研基础设施优先 |

---

## 架构总览

```
                   ┌──────────────────────────────────────────────┐
                   │               z-gw 网关集群                    │
                   │                                              │
  Client ────▶    │  ┌─────────────────────────────────────────┐  │
  HTTP/WS         │  │         Netty Server (:9090)            │  │
                   │  │  HttpServerCodec + Aggregator           │  │
                   │  └──────────────┬──────────────────────────┘  │
                   │                 ▼                              │
                   │  ┌─────────────────────────────────────────┐  │
                   │  │        GatewayHandler                    │  │
                   │  │  ┌─────────┐  ┌──────────┐  ┌────────┐  │  │
                   │  │  │ 路由匹配  │→│ 过滤器链  │→│ 出站转发│  │  │
                   │  │  │ (Radix)  │  │ (SPI)    │  │(Netty) │  │  │
                   │  │  └─────────┘  └──────────┘  └────────┘  │  │
                   │  └─────────────────────────────────────────┘  │
                   └──────────────────────────────────────────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                   ▼
              ┌──────────┐     ┌──────────┐      ┌──────────┐
              │ 后端服务 A │     │ 后端服务 B │      │ 后端服务 C │
              └──────────┘     └──────────┘      └──────────┘

  管理端 (:8888)
  ┌──────────────────────────────────────────┐
  │  Spring Boot Web + z-gw-admin REST API   │
  │  /gw/admin/routes   (CRUD 路由)           │
  │  /gw/admin/metrics  (实时指标)            │
  │  /gw/admin/meta     (SPI 字典)            │
  │  /doc.html          (Knife4j API 文档)    │
  └──────────────────────────────────────────┘

  前端 (:5173)
  ┌──────────────────────────────────────────┐
  │  React 19 + Vite 6 + Ant Design 6        │
  │  总览 → 路由管理 → 指标 → SPI 元数据      │
  └──────────────────────────────────────────┘
```

---

## 设计融合

每一个核心设计决策都经过对成熟开源项目的深入研究，**取各家之长，融入 Java + Netty 的最佳表达**。

| 开源项目 | 借鉴的设计要点 | z-gw 的实现 |
|---------|--------------|-----------|
| [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) | Route + Predicate + Filter 模型，GlobalFilter 机制 | `RouteDefinition` / `PredicateFactory` / `GatewayFilter` / `GlobalFilter` |
| [Apache ShenYu](https://shenyu.apache.org/) | SPI 插件扩展，Selector/Rule 数据结构 | `GatewayFilterFactory` / `PredicateFactory`，`META-INF/services` 自动发现 |
| [APISIX](https://apisix.apache.org/) | radixtree 路由匹配，插件链 | `PathPredicateFactory` 支持 `*` / `**` / `{name}` 占位 |
| [Envoy](https://www.envoyproxy.io/) | xDS 动态配置下发，FilterChain 网络拓扑 | `RouteLocator` SPI（Static / Nacos / Redis / K8s） |
| [Netflix Hystrix](https://github.com/Netflix/Hystrix) | 滑动窗口熔断器状态机 | `SlidingWindowCircuitBreaker`（CLOSED → OPEN → HALF_OPEN） |
| [Resilience4j](https://resilience4j.readme.io/) | 限流器 API 设计 | `RateLimiter` 接口 + 3 种内置实现 |

---

## 模块结构

```
z-gw/
├── z-gw-api                    # 公开 API + SPI 接口层（任何实现版本必须暴露）
│   └── com.zifang.z.gw.api
│       ├── RouteDefinition       # 路由定义（不可变 + Builder）
│       ├── PredicateFactory      # 谓词工厂 SPI
│       ├── GatewayFilter         # 过滤器 SPI
│       ├── GatewayFilterFactory  # 过滤器工厂 SPI
│       ├── LoadBalancer          # 负载均衡 SPI
│       ├── RateLimiter           # 限流器 SPI
│       ├── CircuitBreaker        # 熔断器 SPI
│       └── ServiceDiscovery      # 服务发现 SPI
│
├── z-gw-core                  # 核心引擎（纯 Java + Netty，不依赖 Spring）
│   ├── server/                  # GatewayServer + GatewayHandler
│   ├── http/                    # BackendHttpClient（Netty 出站）
│   ├── router/                  # RouteMatcher + FilterAssembler
│   ├── predicate/               # 6 种内置谓词工厂
│   ├── filter/factory/          # 8 种内置过滤器工厂
│   ├── filter/global/           # 6 个全局过滤器
│   ├── lb/                      # 5 种负载均衡算法
│   ├── ratelimit/               # 3 种限流算法
│   ├── circuitbreaker/          # 滑动窗口熔断器
│   ├── service/                 # URI 解析 + 服务发现
│   └── runtime/                 # GatewayBootstrap 启动门面
│
├── z-gw-spring-boot-starter   # Spring Boot 自动装配（一行依赖启动网关）
├── z-gw-admin                 # 管理 REST API（/gw/admin/**，动态路由 CRUD）
├── z-gw-examples              # 一键启动示例（默认 :8888 admin / :9090 网关）
└── z-gw-admin-frontend        # React 19 + Vite 6 + Ant Design 6 前端管理面板
```

---

## 快速开始

### 30 秒上手（Spring Boot 应用内嵌网关）

```xml
<dependency>
    <groupId>com.zifang</groupId>
    <artifactId>z-gw-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`application.yml`：

```yaml
zgw:
  enabled: true
  server:
    port: 9090          # Netty 网关代理端口
    host: 0.0.0.0
    workerThreads: 0    # 0 = CPU 核数 × 2
  routes:
    - id: user-service
      uri: lb://user-service
      predicates:
        - name: Path
          args: {_genkey_0: /api/users/**}
      filters:
        - name: StripPrefix
          args: {_genkey_0: "1"}
        - name: RequestRateLimiter
          args:
            replenishRate: "100"
            burstCapacity: "200"
            keyResolver: ip
            algorithm: tokenBucket
```

```bash
mvn spring-boot:run
curl http://localhost:9090/health   # → {"status":"UP"}
```

### 启动完整示例（含管理 API + API 文档）

```bash
cd z-gw-examples
mvn spring-boot:run

# 管理 API:  http://localhost:8888/gw/admin/routes
# 网关代理:  http://localhost:9090/demo/get
# API 文档:  http://localhost:8888/doc.html
# 健康检查:  http://localhost:9090/health
```

### 启动前端管理面板

```bash
cd z-gw-admin-frontend
npm install && npm run dev

# 浏览器打开 http://localhost:5173
# Vite dev server 已配置 proxy 到 :8888
```

---

## 路由配置示例

### 1. 基础路由（静态 HTTP 转发）

```yaml
zgw:
  routes:
    - id: httpbin-demo
      uri: http://httpbin.org
      order: 0
      predicates:
        - name: Path
          args: {_genkey_0: /demo/**}
      filters:
        - name: StripPrefix
          args: {_genkey_0: "1"}
        - name: AddResponseHeader
          args: {_genkey_0: "X-Gateway, z-gw"}
```

### 2. 灰度发布（权重 90/10，同 group 流量分割）

```yaml
zgw:
  routes:
    - id: user-service-v1
      uri: http://user-service-v1:8080
      order: 1
      predicates:
        - name: Path
          args: {_genkey_0: /api/users/**}
        - name: Weight
          args: {user_group: "90"}
    - id: user-service-v2-canary
      uri: http://user-service-v2:8080
      order: 2
      predicates:
        - name: Path
          args: {_genkey_0: /api/users/**}
        - name: Weight
          args: {user_group: "10"}
```

### 3. 限流 + 熔断 + 重试（高可用模板）

```yaml
zgw:
  routes:
    - id: api-resilient
      uri: http://api-service:8080
      predicates:
        - name: Path
          args: {_genkey_0: /api/resilient/**}
      filters:
        - name: Hystrix
          args:
            name: api-cb
            errorThresholdPercentage: "50"
            requestVolumeThreshold: "20"
            sleepWindowMs: "5000"
        - name: Retry
          args:
            retries: "3"
            backoffMs: "100"
        - name: RequestRateLimiter
          args:
            replenishRate: "100"
            burstCapacity: "200"
            algorithm: tokenBucket
        - name: StripPrefix
          args: {_genkey_0: "1"}
```

### 4. 多谓词组合（方法 + Header + 路径）

```yaml
zgw:
  routes:
    - id: write-api
      uri: http://api-service:8080
      predicates:
        - name: Path
          args: {_genkey_0: /api/write/**}
        - name: Method
          args: {_genkey_0: "POST,PUT,DELETE"}
        - name: Header
          args: {X-Api-Version: "2\\..*"}
```

---

## 内置能力清单

### 谓词工厂（6 个）

| 名称 | 用途 | 示例 |
|------|------|------|
| `Path` | 路径匹配（`*` 单段 / `**` 多段 / `{name}` 占位） | `Path=/api/**` |
| `Method` | HTTP 方法匹配（多值 OR） | `Method=GET,POST` |
| `Header` | 请求头存在 + 正则匹配 | `Header=X-Trace-Id,.+` |
| `Host` | Host 匹配（支持 `*.example.com` 通配） | `Host=*.example.com` |
| `Weight` | 灰度权重（同 group 按权重比例分流） | `Weight=user_group,90` |
| `Time` | 时间窗口（HH:mm:ss 或 ISO8601） | `Time=08:00:00,22:00:00,Asia/Shanghai` |

### 过滤器工厂（8 个）

| 名称 | order | 用途 |
|------|-------|------|
| `StripPrefix` | 100 | 去掉请求 path 前 N 段 |
| `PrefixPath` | 100 | 给请求 path 加前缀 |
| `RewritePath` | 200 | 正则重写 path |
| `AddRequestHeader` | 300 | 往出站请求加 header |
| `AddResponseHeader` | 800 | 往客户端响应加 header |
| `RequestRateLimiter` | -100 | 限流（tokenBucket / slidingWindow / fixedWindow） |
| `Hystrix` | 500 | 熔断（滑动窗口，失败率超阈值短路） |
| `Retry` | 600 | 重试（502 / 503 / 504，指数退避） |

### 全局过滤器（6 个）

| 名称 | order | 用途 |
|------|-------|------|
| `Tracing` | -1000 | 链路追踪（生成/透传 X-Request-Id） |
| `Metrics` | -900 | Micrometer 指标采集（QPS + RT + 状态码） |
| `Cors` | -800 | CORS 跨域响应头 |
| `Logging` | -700 | 请求日志（`[reqId] METHOD path → route cost`） |
| `ErrorHandling` | 900 | 异常兜底（GatewayException → 对应 HTTP 状态码） |
| `NettyProxy` | 999 | 出站转发（实际代理到后端，最晚执行） |

### 负载均衡（5 种）

| 名称 | 算法 | 适用场景 |
|------|------|---------|
| `roundRobin` | 轮询 | 后端机器性能相近（默认） |
| `random` | 随机 | 后端无状态，最简单 |
| `weighted` | 平滑加权轮询（SWRR） | 异构机器，按权重分配 |
| `ipHash` | IP 哈希 | 会话保持，session 粘性 |
| `leastConnections` | 最少连接数 | 长连接场景（WebSocket / gRPC） |

### 限流（3 种）

| 名称 | 算法 | 特点 |
|------|------|------|
| `tokenBucket` | 令牌桶 | 允许突发，稳态 QPS 可控（默认） |
| `slidingWindow` | 滑动窗口 | 精确，避免临界突刺 |
| `fixedWindow` | 固定窗口 | 最快，有边界突刺 |

### 熔断器

```
CLOSED (正常) ──失败率 > 50% & 流量 > 20──▶ OPEN (熔断, 快速失败)
    ▲                                            │
    │                                    sleepWindow (5s)
    │                                            ▼
    └──────── 成功 × 3 ◀──── HALF_OPEN (半开, 试探性放行)
```

### URI Scheme

| 形式 | 行为 |
|------|------|
| `http://host:port` | 静态单实例转发 |
| `https://host:port` | 静态单实例转发（HTTPS） |
| `lb://serviceName` | ServiceDiscovery + LoadBalancer 选一实例 |
| `forward://localPath` | 转发到本地路径（预留） |

---

## 管理 API

通过 `z-gw-admin` 模块提供 REST API，在 Spring Boot Web 容器中暴露：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/gw/admin/routes` | `GET` | 列出全部路由 |
| `/gw/admin/routes` | `POST` | 创建路由 |
| `/gw/admin/routes/{id}` | `GET` | 获取单条路由 |
| `/gw/admin/routes/{id}` | `PUT` | 更新路由 |
| `/gw/admin/routes/{id}` | `DELETE` | 删除路由 |
| `/gw/admin/routes/reload` | `POST` | 批量替换路由 |
| `/gw/admin/routes/stats` | `GET` | 路由统计（总数 / 启用 / 禁用） |
| `/gw/admin/meta/predicates` | `GET` | 全部内置谓词工厂列表 |
| `/gw/admin/meta/status` | `GET` | 网关运行状态 |
| `/gw/admin/metrics/summary` | `GET` | 聚合指标（总请求 / 平均 RT） |
| `/gw/admin/metrics/routes` | `GET` | 按路由维度汇总 |

Knife4j API 文档：`http://localhost:8888/doc.html`

---

## 可观测性

### Micrometer 指标

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `zgw.request.duration` | Timer | route, method, status | 请求 RT（含 P99 分位直方图） |
| `zgw.request.total` | Counter | route, method, status | 请求计数 |

### Actuator 端点

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus"
```

- `/actuator/health` — 网关健康检查
- `/actuator/metrics` — Micrometer 指标
- `/actuator/prometheus` — Prometheus 抓取端点

---

## 与业界对比

| 维度 | z-gw | Spring Cloud Gateway | Apache APISIX | Envoy |
|------|------|---------------------|--------------|-------|
| **语言** | Java | Java | Lua / Go | C++ |
| **运行时** | Netty (直连) | Netty + WebFlux | Nginx + Lua | 自研 |
| **配置方式** | yml + Admin API | yml | Admin API + ETCD | xDS |
| **服务发现** | SPI（Static / Nacos / Consul / K8s） | Eureka / Consul / Nacos | DNS / Consul / ETCD | xDS |
| **限流** | 3 种内置（令牌桶/滑动窗口/固定窗口） | Redis（需外部） | 多种 Lua 插件 | 内置 |
| **熔断** | 滑动窗口（内置） | Resilience4j / Sentinel | 插件 | 内置 |
| **灰度** | 权重 + Header + Time | 权重 | 多种 | Header |
| **Admin UI** | React 19 + Antd 6（内置） | 无 | Dashboard（需部署） | 无 |
| **Spring Boot 集成** | 一行依赖启动 | 原生支持 | 需要额外 SDK | 不支持 |
| **响应式依赖** | 无（纯 Netty） | WebFlux（必须） | 无 | 无 |

**适用场景**：Java 技术栈团队，需要 100% 自研可控 + Spring Boot 无缝集成 + 内置管理面板的 API 网关。

---

## 测试

```bash
cd z-gw
mvn test                    # 全模块测试（25 个用例）
mvn test -pl z-gw-core      # 单模块测试
```

| 测试类 | 数量 | 覆盖范围 |
|--------|------|---------|
| `PredicateFactoryTest` | 7 | Path / Method / Header / Host / Weight / Time 谓词 |
| `RouteMatcherTest` | 6 | 路由匹配 + 排序 + 灰度权重 + 禁用路由 |
| `LoadBalancerTest` | 4 | Random / RoundRobin / IP Hash / 空列表 |
| `RateLimiterTest` | 4 | TokenBucket / SlidingWindow / FixedWindow / Key 隔离 |
| `CircuitBreakerTest` | 4 | CLOSED → OPEN 状态转换 + Key 隔离 |

---

## 设计原则

| 原则 | 实现 |
|------|------|
| **Core 纯洁性** | `z-gw-core` 不依赖 Spring，纯 Java + Netty，`spring-boot-starter` 仅做包装 |
| **SPI 优先** | 7 个核心 SPI（Predicate / FilterFactory / LoadBalancer / RateLimiter / CircuitBreaker / ServiceDiscovery / RouteLocator）全部可扩展 |
| **不可变数据** | `RouteDefinition` / `PredicateDefinition` / `FilterDefinition` / `ServiceInstance` 全部不可变 + Builder 构造，线程安全 |
| **无锁优先** | 令牌桶 / 限流器 / 计数器使用 `AtomicLong` CAS，高频路径零锁 |
| **自研基础设施** | 不引入 Spring WebFlux / Sentinel / Resilience4j 等重依赖 |

---

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 运行时 | Netty | 4.1.100.Final |
| 框架 | Spring Boot | 2.7.18 |
| Java | JDK | 8 |
| 前端 | React 19 + Vite 6 + Ant Design 6 | — |
| 工具库 | z-util (core / http / parser-json) | 1.0.9 |
| 指标 | Micrometer + Prometheus | 1.12.13 |
| API 文档 | Knife4j (OpenAPI 3) | 4.1.0 |
| 日志 | Log4j2 | 2.25.4 |

---

## Roadmap

| 版本 | 状态 | 内容 |
|------|------|------|
| **v1** | ✅ 已完成 | 路由 + 6 谓词 + 8 过滤器 + 5 LB + 3 限流 + 熔断 + Admin API + 前端 |
| **v2** | 🔜 计划中 | 集群同步（Nacos / Redis Pub/Sub），多节点路由广播 |
| **v3** | 📋 规划中 | Nacos / Consul 服务发现适配器 |
| **v4** | 💡 构想中 | HTTP/2 支持（基于 Netty Http2Channel） |
| **v5** | 💡 构想中 | gRPC 代理（grpc-gateway 风格） |
| **v6** | 💡 构想中 | Wasm 插件运行时 |

---

## 维护

- **模块维护人**：z-gw 团队
- **反馈渠道**：GitHub Issues ([z-opc-foundation/z-gw](https://github.com/z-opc-foundation/z-gw))
- **文档**：本 README + 模块 JavaDoc
- **设计参考源码**（`yuque/开源研究/002_源码分析/`）：
  - `556_spring-cloud-gateway/` — Spring Cloud Gateway 源码分析与复刻指南
  - `646_kgateway/` — kgateway K8s Gateway API 架构分析
  - `479_shenyu/` — Apache ShenYu SPI 插件扩展机制
  - `312_apisix/` — APISIX radixtree 路由匹配
  - `559_envoy/` — Envoy xDS 动态配置下发
