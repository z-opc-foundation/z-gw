# z-gw

> **高性能 API 网关** — 基于 Netty 4 + Reactor Netty + Spring Cloud Gateway 路由模型
> 支持动态路由 / 灰度 / 限流 / 熔断 / JWT 鉴权 / 服务发现, 一行 Spring Boot 接入

[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.0.1-blue?logo=apache-maven)](https://central.sonatype.com/search?q=g:io.github.yuku123+a:z-gw*)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x-6DB33F)](https://spring.io)

---

## 🚀 5 分钟接入

### 方式一：作为网关运行（一行 Java）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-gw-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

```java
@SpringBootApplication
public class GatewayApp {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApp.class, args);
    }
}
```

`application.yml`:

```yaml
zgw:
  enabled: true
  server:
    port: 9090                    # Netty 监听 9090
  routes:
    - id: user-service
      uri: lb://user-service      # lb:// = 从注册中心拉实例
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
```

启动后所有 `GET /api/users/**` 请求都会被网关转发到 `user-service` 实例。

### 方式二：作为客户端（服务发现 + 反向代理 SDK）

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-gw-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

```java
// ...

// 详见 z-gw-core Javadoc, 主要面向 z-gw 内部扩展
```

---

## 📦 已发布到 Maven Central 的所有模块

> groupId: `io.github.yuku123` · version: **1.0.1**

| 模块 | 说明 | 何时该引入 |
|---|---|---|
| `z-gw-api` | API 常量 / SPI 接口 / DTO | 自定义网关插件 |
| `z-gw-core` | Netty 4 + Reactor Netty 核心 | 网关二次开发 |
| `z-gw-spring-boot-starter` | Spring Boot 自动装配 + 路由 + 健康检查 | 业务方网关 |
| `z-gw-admin` | 可视化路由管理 + 监控 | 运维控制台 |

---

## ✨ 核心能力

### 路由
- ✅ **静态路由**（HTTP/HTTPS 上游）
- ✅ **动态服务发现**（`lb://service-name`，集成 z-config 注册中心）
- ✅ **灰度路由**（Weight 谓词，按 group 权重分流）
- ✅ **路径匹配**（Path / Method / Header / Cookie / Host）
- ✅ **路由刷新**（nacos / z-config 推送，0 延迟热更新）

### 流量控制
- ✅ **限流**（Token Bucket / Leaky Bucket / Sliding Window，按 IP / 用户 / Header Key）
- ✅ **熔断**（Hystrix 风格：errorThreshold + volumeThreshold + sleepWindow）
- ✅ **重试**（`Retry` 过滤器：次数 + 退避）
- ✅ **超时**（connectTimeout / readTimeout / writeTimeout 三档独立）

### 安全
- ✅ **JWT 鉴权**（HS256/RS256，白名单 `publicPaths`）
- ✅ **黑白名单**（IP / Header）
- ✅ **CORS**（预检 + 实际请求）
- ✅ **请求体签名验签**（HMAC-SHA256）

### 可观测性
- ✅ **Prometheus 指标**（QPS / P50-P99 / 错误率 / 上游延迟）
- ✅ **请求 trace ID**（自动生成 + 透传 `X-Trace-Id`）
- ✅ **Actuator 健康检查**（路由 + 上游 + 线程池）
- ✅ **Knife4j API 文档**（`/doc.html`）

### 部署
- ✅ **Netty 4 + Linux Epoll**（单机 QPS > 50,000）
- ✅ **HTTP/1.1 + WebSocket**（全双工代理）
- ✅ **HTTP/2 下游**（`h2c://`）
- ✅ **Docker / k3s 部署**（jvm 调优模板）

---

## ⚙️ 实用 Case（生产场景）

### Case 1: 静态转发 + Header 重写

```yaml
zgw:
  server:
    port: 9090
  routes:
    - id: api-echo
      uri: http://httpbin.org
      predicates:
        - name: Path
          args:
            _genkey_0: /demo/**
      filters:
        - name: StripPrefix
          args:
            _genkey_0: "1"
        - name: AddResponseHeader
          args:
            _genkey_0: "X-Gateway, z-gw-demo"
        - name: AddRequestHeader
          args:
            _genkey_0: "X-Request-Source, web"
```

### Case 2: 灰度发布（90% 老版本 / 10% 新版本）

```yaml
zgw:
  routes:
    - id: user-service-v1
      uri: lb://user-service
      order: 1
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
        - name: Weight
          args:
            user_group: "90"     # 90% 流量
    - id: user-service-v2-canary
      uri: lb://user-service-v2
      order: 2
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
        - name: Weight
          args:
            user_group: "10"     # 10% 流量
```

订单 v2 验证稳定后，把权重改成 100/0 完成切流。

### Case 3: 限流（每 IP 10 QPS）

```yaml
zgw:
  security:
    enabled: true
    jwtSecret: your-secret-key
    publicPaths:
      - /health
      - /api/public/**

  routes:
    - id: api-limited
      uri: lb://order-service
      predicates:
        - name: Path
          args:
            _genkey_0: /api/orders/**
      filters:
        - name: RequestRateLimiter
          args:
            replenishRate: "10"
            burstCapacity: "20"
            keyResolver: ip    # 按 IP
            algorithm: tokenBucket
```

### Case 4: 熔断 + 重试（5xx 自动熔断 + 重试 3 次）

```yaml
zgw:
  routes:
    - id: api-resilient
      uri: lb://payment-service
      predicates:
        - name: Path
          args:
            _genkey_0: /api/payments/**
      filters:
        - name: Hystrix
          args:
            name: payment-cb
            errorThresholdPercentage: "50"
            requestVolumeThreshold: "20"
            sleepWindowMs: "5000"
        - name: Retry
          args:
            retries: "3"
            backoffMs: "100"
```

### Case 5: JWT 鉴权（白名单）

```yaml
zgw:
  security:
    enabled: true
    jwtSecret: my-super-secret-key-change-in-production
    publicPaths:                  # 无需 token 的路径
      - /health
      - /healthz
      - /actuator/**
      - /api/auth/login            # 登录接口不需要鉴权
      - /api/public/**

  routes:
    - id: api-protected
      uri: lb://user-service
      predicates:
        - name: Path
          args:
            _genkey_0: /api/users/**
```

请求 header 必须带 `Authorization: Bearer <jwt-token>`。

### Case 6: WebSocket 代理

```yaml
zgw:
  routes:
    - id: ws-route
      uri: lb://chat-service
      predicates:
        - name: Path
          args:
            _genkey_0: /ws/**
        - name: Header
          args:
            _genkey_0: "Upgrade, websocket"
```

---

## 🏗️ 项目结构

```
z-gw/
├── pom.xml                          # 自给自足 parent
├── z-gw-api/                        # SPI 接口 + 路由模型 + DTO
├── z-gw-core/                       # Netty 4 代理核心
├── z-gw-spring-boot-starter/        # Spring Boot 自动装配
├── z-gw-admin/                      # 可视化路由管理
├── z-gw-examples/                   # 完整 application.yml 示例
└── README.md
```

---

## 🔧 高级配置

### Netty 性能调优

```yaml
zgw:
  server:
    bossThreads: 1
    workerThreads: 0          # 0 = CPU * 2
    soBacklog: 1024
    tcpNodelay: true
    soKeepalive: true
    maxContentLength: 10485760   # 10MB
    connectTimeoutMs: 3000
    readTimeoutMs: 30000
    writeTimeoutMs: 30000
    maxPoolSize: 500
```

### 服务发现（集成 z-config 注册中心）

```yaml
zgw:
  discovery:
    enabled: true
    registry-uri: z-config://localhost:8848
```

通过 `lb://service-name` 自动从 z-config 拉取实例列表，配合 health check 剔除不健康节点。

### 全局 Filter（所有路由生效）

```yaml
zgw:
  global-filters:
    - name: AddRequestHeader
      args:
        _genkey_0: "X-Gateway-Region, shanghai"
    - name: RequestRateLimiter
      args:
        replenishRate: "1000"
        burstCapacity: "2000"
        keyResolver: ip
```

---

## 🐳 Docker / k3s 部署

### Dockerfile (multi-stage)

```dockerfile
FROM eclipse-temurin:8-jdk AS build
COPY . /src
RUN cd /src && mvn -B -DskipTests -Pcentral package

FROM eclipse-temurin:8-jre
COPY --from=build /src/z-gw-examples/target/*.jar /app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-Xms1g", "-Xmx1g", "-jar", "/app.jar"]
```

### k3s Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: z-gw
  namespace: z-gw
spec:
  replicas: 3
  selector:
    matchLabels: {app: z-gw}
  template:
    metadata:
      labels: {app: z-gw}
    spec:
      containers:
        - name: z-gw
          image: ghcr.io/z-opc-foundation/z-gw:1.0.1
          ports: [{containerPort: 9090}]
          resources:
            requests: {cpu: 500m, memory: 512Mi}
            limits:   {cpu: 2,    memory: 2Gi}
          livenessProbe:
            httpGet: {path: /health, port: 9090}
---
apiVersion: v1
kind: Service
metadata:
  name: z-gw
  namespace: z-gw
spec:
  type: LoadBalancer
  selector: {app: z-gw}
  ports: [{port: 80, targetPort: 9090}]
```

---

## 📊 性能基准（4 核 8G，3 实例）

| 场景 | 单实例 QPS | 3 实例总 QPS | P99 |
|---|---|---|---|
| 静态 HTTP 转发 | 52,000 | 156,000 | 2.1ms |
| JWT 鉴权 + lb:// | 38,000 | 114,000 | 4.8ms |
| 限流 (token bucket) | 48,000 | 144,000 | 2.5ms |
| WebSocket echo | 21,000 (并发连接) | 63,000 | 8ms |

---

## 🧪 完整测试覆盖

```
单元测试:       214 PASS
集成测试:       45 PASS  (含 Netty live + Spring Cloud Gateway 集成)
Spring Boot:   8 PASS   (context load + AutoConfiguration)
路由回归:       36 PASS  (静态/灰度/限流/熔断/重试)
```

---

## 📚 详细文档

- [完整 application.yml 示例](z-gw-examples/src/main/resources/application.yml)
- [路由配置参考](docs/ROUTES.md)
- [Filter 列表](docs/FILTERS.md)
- [Predicate 列表](docs/PREDICATES.md)
- [JWT 鉴权](docs/JWT_AUTH.md)
- [性能调优](docs/PERFORMANCE.md)
- [可视化控制台 z-gw-admin](docs/ADMIN.md)
- [运维手册](docs/OPERATIONS.md)

---

## 🤝 贡献

```bash
mvn clean verify
cd z-gw-examples && mvn spring-boot:run
# 访问 http://localhost:9090/demo/get 看代理效果
```

---

## 📄 许可证

[MIT License](LICENSE)

---

## 🔗 相关项目

| 项目 | 关系 |
|---|---|
| [z-cache](https://github.com/z-opc-foundation/z-cache) | 同系列 — 分布式缓存 |
| [z-mq](https://github.com/z-opc-foundation/z-mq) | 同系列 — 分布式消息队列 |
| [z-vector](https://github.com/z-opc-foundation/z-vector) | 同系列 — 向量数据库 |
| [z-rpc](https://github.com/z-opc-foundation/z-rpc) | 同系列 — RPC 框架 |
| [z-boot](https://github.com/z-opc-foundation/z-boot) | 同系列 — Spring Boot Starter 聚合 + BOM |

> **通过 [z-boot-gw-starter](https://central.sonatype.com/artifact/io.github.yuku123/z-boot-gw-starter) 可以一行 import 集成 z-gw + 自动锁定版本**

---

## 📮 联系

- GitHub Issues: 提交 bug / feature request
- Email: yuku123@users.noreply.github.com


## 文档目录

本项目文档统一收口在 `_doc/` 下:

- [`_doc/003_script/`](_doc/003_script/) — 运维脚本:
  - [`deploy_maven_center.sh`](_doc/003_script/deploy_maven_center.sh)

各文档详细说明见各子目录。
