# z-gw — 自研高性能 API 网关

> **One Company Unified Gateway** — 基于 Netty + Spring Boot 生态的高性能 API 网关,
> 设计融合 Spring Cloud Gateway / Apache ShenYu / APISIX / Envoy / 自研 Netty 的核心思想。

![version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-blue)
![spring-boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-green)
![netty](https://img.shields.io/badge/Netty-4.1.100-purple)
![java](https://img.shields.io/badge/Java-1.8-orange)

---

## 这是什么

z-gw 是一个基于 **Netty 异步非阻塞运行时 + Spring Boot 自动装配** 的高性能 API 网关,
作为微服务架构的统一入口,提供 **请求路由、负载均衡、限流熔断、灰度发布、链路追踪、指标采集**
等核心能力。

**融合的成熟设计**:

| 来源 | 借鉴的设计要点 |
|------|---------------|
| [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) | Route + Predicate + Filter 模型, GlobalFilter 机制 |
| [Apache ShenYu](https://shenyu.apache.org/) | SPI 插件扩展, Selector/Rule 数据结构 |
| [APISIX](https://apisix.apache.org/) | radixtree 路由匹配, 插件链 |
| [Envoy](https://www.envoyproxy.io/) | xDS 动态配置下发思路, FilterChain 网络拓扑 |
| [Netflix Hystrix](https://github.com/Netflix/Hystrix) | 滑动窗口熔断器状态机 (CLOSED/OPEN/HALF_OPEN) |
| [Resilience4j](https://resilience4j.readme.io/) | 限流器 API 设计风格 |

**自研基础设施优先**:

- `z-util-core / http / parser-json` — 自研工具库
- 自研 Netty 运行时 — 不引入 Spring WebFlux,避免响应式编程心智负担
- 统一 `com.zifang.z.gw.*` 命名 — 与 z-opc 一致

---

## 模块结构

```
z-gw-parent (parent, packaging=pom)
├── z-gw-api              公开 API + SPI 接口,任何实现版本必须暴露
├── z-gw-core              Netty 服务器、路由匹配、谓词/过滤器工厂、负载均衡、限流、熔断、服务发现
├── z-gw-spring-boot-starter  Spring Boot 自动装配,一行依赖启动网关
├── z-gw-admin             管理 REST API (/gw/admin/**),动态路由 CRUD + 指标查询
├── z-gw-examples          一键启动示例,内置网关 + admin,默认 :8888 admin/:9090 网关
└── z-gw-admin-frontend    React 19 + Vite 6 + Ant Design 6 前端管理面板
```

---

## 快速开始

### 一行依赖启动 (Spring Boot 应用)

```xml
<dependency>
    <groupId>com.zifang</groupId>
    <artifactId>z-gw-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`application.yml`:

```yaml
zgw:
  enabled: true
  server:
    port: 9090          # Netty 网关代理端口
    host: 0.0.0.0
    workerThreads: 0    # 0 = CPU * 2
  routes:
    - id: user-service
      uri: lb://user-service
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
      filters:
        - name: StripPrefix
          args:
            _genkey_0: "1"
        - name: RequestRateLimiter
          args:
            replenishRate: "100"
            burstCapacity: "200"
            keyResolver: ip
            algorithm: tokenBucket
```

### 直接启动示例 (含管理面板)

```bash
cd z-gw-examples
mvn spring-boot:run

# 启动后:
# - Spring Boot Web (admin API): http://localhost:8888
# - z-gw Netty 网关代理端口:    http://localhost:9090
# - API 文档 (Knife4j):         http://localhost:8888/doc.html
# - 健康检查:                    http://localhost:9090/health

# 测试代理
curl http://localhost:9090/demo/get
# → 转发到 demo-echo 路由的后端

# 查询路由
curl http://localhost:8888/gw/admin/routes
```

### 前端管理面板启动

```bash
cd z-gw-admin-frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173 (vite dev server 已配 proxy 到 :8888)
```

---

## 路由配置示例

### 1. 基础静态路由

```yaml
zgw:
  routes:
    - id: demo
      uri: http://httpbin.org
      predicates:
        - name: Path
          args:
            _genkey_0: /demo/**
      filters:
        - name: StripPrefix
          args:
            _genkey_0: "1"
```

### 2. 灰度发布(权重 90/10)

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

### 3. 限流 + 熔断 + 重试 (高可用模板)

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

### 4. 多谓词组合(方法 + 头)

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

## 内置 SPI 列表

### 谓词工厂 (PredicateFactory)

| 名称 | 用途 | yml 示例 |
|------|------|---------|
| `Path` | 路径匹配,支持 `*` / `**` / `{name}` 占位 | `Path=/api/**` |
| `Method` | HTTP 方法,多值 OR | `Method=GET,POST` |
| `Header` | Header 存在 + 正则匹配 | `Header=X-Trace-Id,.+` |
| `Host` | Host 匹配,支持 `*.example.com` | `Host=*.example.com` |
| `Weight` | 灰度权重,需配 group | `Weight=user_group,90` |
| `Time` | 时间窗口 (HH:mm:ss 或 ISO8601) | `Time=08:00:00,22:00:00,Asia/Shanghai` |

### 过滤器工厂 (GatewayFilterFactory)

| 名称 | order | 用途 |
|------|-------|------|
| `StripPrefix` | 100 | 把请求 path 前 N 段去掉 |
| `PrefixPath` | 100 | 给请求 path 加前缀 |
| `RewritePath` | 200 | 正则重写 path |
| `AddRequestHeader` | 300 | 往出站请求加 header |
| `AddResponseHeader` | 800 | 往客户端响应加 header |
| `RequestRateLimiter` | -100 | 限流 (tokenBucket / slidingWindow / fixedWindow) |
| `Hystrix` | 500 | 熔断(滑动窗口) |
| `Retry` | 600 | 重试 (502/503/504) |

### 全局过滤器 (GlobalFilter,内置)

| 名称 | order | 用途 |
|------|-------|------|
| `Tracing` | -1000 | 链路追踪 (生成/透传 X-Request-Id) |
| `metrics` | -900 | Micrometer 指标采集 |
| `cors` | -800 | CORS 跨域响应 |
| `logging` | -700 | 请求日志 |
| `errorHandling` | 900 | 异常兜底 → 对应 HTTP 状态码 |
| `nettyProxy` | 999 | 出站转发(实际代理后端) |

### 负载均衡

| 名称 | 算法 |
|------|------|
| `random` | 随机 |
| `roundRobin` | 轮询(默认) |
| `weighted` | 平滑加权轮询(SWRR) |
| `ipHash` | IP 哈希(会话保持) |
| `leastConnections` | 最少连接数 |

### 限流算法

| 名称 | 算法 |
|------|------|
| `tokenBucket` | 令牌桶(允许突发) |
| `slidingWindow` | 滑动窗口(精确) |
| `fixedWindow` | 固定窗口(最快) |

### URI Scheme

| 形式 | 行为 |
|------|------|
| `http://host:port` | 静态单实例 HTTP 转发 |
| `https://host:port` | 静态单实例 HTTPS 转发 |
| `lb://serviceName` | 通过 ServiceDiscovery + LoadBalancer 选一 |
| `forward://localPath` | 转发到本地路径(预留) |

---

## 管理 API (z-gw-admin)

通过 `z-gw-admin` 模块提供 REST API,在 Spring Boot Web 容器中暴露。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/gw/admin/routes` | GET | 列出全部路由 |
| `/gw/admin/routes` | POST | 创建路由 |
| `/gw/admin/routes/{id}` | PUT | 更新路由 |
| `/gw/admin/routes/{id}` | DELETE | 删除路由 |
| `/gw/admin/routes/{id}` | GET | 获取单条路由 |
| `/gw/admin/routes/reload` | POST | 批量替换路由 |
| `/gw/admin/routes/stats` | GET | 路由统计 |
| `/gw/admin/meta/predicates` | GET | 列出全部内置谓词 |
| `/gw/admin/meta/status` | GET | 网关运行状态 |
| `/gw/admin/metrics/summary` | GET | 聚合指标 |
| `/gw/admin/metrics/routes` | GET | 按路由维度指标 |

Knife4j API 文档: `http://localhost:8888/doc.html`

---

## 测试

```bash
cd z-gw
mvn test                 # 全模块测试
mvn test -pl z-gw-core   #单模块测试

# 已通过测试 (25+):
# - PredicateFactoryTest (7)         路径/方法/头/Host/Weight/Time
# - RouteMatcherTest (6)             路由匹配 + 排序 + 灰度
# - LoadBalancerTest (4)             random/roundRobin/ipHash/empty
# - RateLimiterTest (4)              tokenBucket/slidingWindow/fixedWindow
# - CircuitBreakerTest (4)           CLOSED/OPEN/isolation
```

---

## 可观测性

通过 Micrometer 暴露:

- `zgw.request.duration` (Timer) — 请求 RT,带 `route` / `method` / `status` 标签
- `zgw.request.total` (Counter) — 请求计数
- Spring Boot Actuator 端点:`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`

生产部署配 Prometheus:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus"
```

---

## 与业界对比

| 维度 | z-gw | Spring Cloud Gateway | Apache APISIX | Envoy |
|------|------|---------------------|--------------|--------|
| 语言 | Java | Java | Lua | C++ |
| 运行时 | Netty | Netty + WebFlux | Nginx + Lua | 自研 |
| 配置方式 | yml + Admin API | yml | Admin API + ETCD | xDS |
| 服务发现 | SPI (Static/Nacos/...) | Eureka/Consul/Nacos | DNS/Consul/ETCD | xDS |
| 限流 | 3 种内置 | Redis | 多种 Lua 插件 | 内置 |
| 熔断 | 滑动窗口 | Resilience4j / Sentinel | 插件 | 内置 |
| 灰度 | 权重 + Header | 权重 | 多种 | Header |
| Admin UI | React 19 + Antd 6 | 无 | 有 (DashBoard) | 无 |

**适用场景**: 小型团队需要 100% 自研可控 + Java 单语言 + Spring Boot 集成友好。

---

## 设计原则

### Core / Engine 纯洁性
`z-gw-core` 不依赖 Spring,纯 Java + Netty 实现,spring-boot-starter 仅做包装。

### SPI 优先
- `PredicateFactory` / `GatewayFilterFactory` / `ServiceDiscovery` / `LoadBalancer` / `RateLimiter` / `CircuitBreaker` / `RouteLocator` 全部 SPI 化
- 通过 `META-INF/services` 自动发现,或通过 Spring 容器显式注册

### 不可变对象 + Builder
所有数据类(`RouteDefinition`、`PredicateDefinition`、`FilterDefinition`、`ServiceInstance`) 都是不可变,通过 Builder 构造,线程安全。

### 单线程无锁优先
令牌桶 / 限流器 / 计数器等高频路径使用 `AtomicLong` CAS,避免锁。

---

## Roadmap

- [x] **v1**: 路由 + 谓词 + 过滤器链 + 限流 + 熔断 + 5 LB + 3 限流 + 管理 API
- [ ] **v2**: 集群同步 (Nacos / Redis Pub/Sub), 多节点路由广播
- [ ] **v3**: Nacos / Consul 服务发现适配
- [ ] **v4**: HTTP/2 (基于 Netty Http2Channel)
- [ ] **v5**: gRPC 代理 (grpc-gateway 风格)
- [ ] **v6**: Wasm 插件运行时

---

## 维护

- 模块维护人: z-gw 团队
- 反馈渠道: GitLab Issues
- 文档: 本 README + 模块 JavaDoc
- 设计参考: `z-biz-creator/z-biz-learning-yuque-loc/yuque/开源研究/002_源码分析/`
  - `556_spring-cloud-gateway/` — Spring Cloud Gateway 源码分析
  - `646_kgateway/` — kgateway K8s Gateway API 思路
  - `479_shenyu/` — Apache ShenYu SPI 插件扩展
  - `312_apisix/` — APISIX radixtree 路由
  - `559_envoy/` — Envoy xDS 动态配置
