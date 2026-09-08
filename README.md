# Z-GW 高性能 API 网关

Z-GW 是一个基于 Netty 构建的高性能 API 网关，作为微服务架构的统一入口，提供请求路由、负载均衡、限流熔断、认证鉴权等核心能力。

## 特性

- **高性能**: 基于 Netty 的异步非阻塞架构，单机支持 10万+ QPS
- **动态路由**: 支持基于 URL、Header、Query 参数的路由匹配
- **负载均衡**: 内置多种负载均衡算法 (轮询、随机、加权、最少连接等)
- **限流熔断**: 支持令牌桶、滑动窗口等限流算法，自动熔断降级
- **插件扩展**: 基于责任链的过滤器模式，支持自定义插件
- **配置热更新**: 支持运行时配置变更，无需重启

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+

### 构建项目

```bash
mvn clean package -DskipTests
```

### 运行网关

```bash
# 使用默认配置
java -jar z-gw-starter/target/z-gw-starter-1.0.0-SNAPSHOT.jar

# 指定配置文件
java -Dgateway.config=/path/to/gateway.yaml -jar z-gw-starter/target/z-gw-starter-1.0.0-SNAPSHOT.jar
```

### 测试网关

```bash
# 健康检查
curl http://localhost:8080/health

# 测试路由 (假设配置了 /api/users/** 路由)
curl http://localhost:8080/api/users/list
```

## 配置说明

### 配置文件示例

```yaml
server:
  port: 8080
  bossThreads: 1
  workerThreads: 0  # 0 = 使用 Netty 默认值
  soBacklog: 1024
  soKeepalive: true
  tcpNodelay: true
  maxContentLength: 10485760  # 10MB

router:
  routes:
    - id: user-service
      path: /api/users/**
      method: "*"
      backend: http://localhost:8081
      stripPrefix: true

    - id: order-service
      path: /api/orders/**
      method: "*"
      backend: http://localhost:8082
      stripPrefix: true
```

### 配置项说明

| 配置项                     | 说明               | 默认值               |
|-------------------------|------------------|-------------------|
| server.port             | 网关监听端口           | 8080              |
| server.bossThreads      | Netty boss 线程数   | 1                 |
| server.workerThreads    | Netty worker 线程数 | 0 (CPU cores * 2) |
| server.soBacklog        | TCP backlog 队列大小 | 1024              |
| server.maxContentLength | 最大请求体大小          | 10MB              |
| router.routes           | 路由规则列表           | []                |

## 架构设计

详见 [架构设计文档](_doc/架构设计.md)

## 开发计划

- [x] Phase 1: MVP 基础功能 (HTTP Server, 基础路由)
- [ ] Phase 2: 核心功能 (动态路由, 负载均衡, 限流熔断)
- [ ] Phase 3: 企业级特性 (服务发现, 配置中心, 认证鉴权)
- [ ] Phase 4: 高级特性 (插件系统, 多协议支持, 边缘计算)

## 贡献指南

欢迎提交 Issue 和 PR！

## 许可证

Apache License 2.0