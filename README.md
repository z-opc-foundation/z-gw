# z-gw

> API 网关

轻量 API 网关, 支持路由分发、负载均衡 (随机/最少连接)、指标采集

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **项目** | z-gw |
| **分类** | 基础设施 · API 网关 |
| **父项目** | z-opc (com.zifang:z-opc:1.0.0-SNAPSHOT) |
| **默认端口** | `8888` |
| **文档维护** | z-opc-foundation |
| **最近更新** | 2026-09-06 |

---

## 🎯 核心功能

轻量 API 网关, 支持路由分发、负载均衡 (随机/最少连接)、指标采集

详细功能特性详见各子模块 README 或源码注释。

---

## 🏗️ 项目结构

```
z-gw/
├── pom.xml                      # 根 POM (引用 z-opc 父项目)
├── README.md                    # 本文档
├── MODULE_NOTE.md               # 来源说明 (从 z-opc 拆分)
```

子模块列表:

| 模块 | 职责 |
|------|------|
| `z-gw-core/` | 网关核心: 路由/负载均衡/指标 |
| `z-gw-starter/` | Spring Boot 启动模块 |

---

## 🔧 技术栈

- Java 8+
- Netty
- Spring Boot Starter

---

## 🚀 快速开始

### 前置条件

- JDK 8+ (推荐 JDK 17)
- Maven 3.6+
- 端口 `8888` 未被占用

### 编译

```bash
# 在 z-opc 父项目下编译 (推荐)
cd /Users/zifang/workplace/idea_workplace/z-opc
mvn clean install -pl :z-opc -am -DskipTests

# 单独编译本模块 (需 ../pom.xml 父项目可用)
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-gw
mvn clean compile
```

### 运行

```bash
# 启动主服务 (根据项目类型选择)
mvn -pl <启动模块> spring-boot:run
# 或
java -jar <启动模块>/target/*.jar
```

---

## 📦 模块说明

z-gw 由以下子模块组成:

| `z-gw-core/` | 网关核心: 路由/负载均衡/指标 |
| `z-gw-starter/` | Spring Boot 启动模块 |

各模块职责详见各子目录下的 `pom.xml` 和源码。

---

## 🧪 测试

```bash
mvn test
```

测试覆盖:
- 单元测试: 各核心服务类
- 集成测试: 端到端调用链路
- 性能测试: 详见 `/src/test` 下的 `*PerformanceTest.java`

---

## 🔌 API 接口

API 接口定义在各子模块的 `controller` 包下。

启动后访问 `http://localhost:8888/swagger-ui.html` 或 `/doc.html` (knife4j) 查看完整 API 文档。

---

## 🐳 部署

### Docker

```bash
# 构建镜像
docker build -t z-gw:latest .

# 运行容器
docker run -d -p 8888:8888 --name z-gw z-gw:latest
```

### 配置

主要配置文件:
- `application.yml` - Spring Boot 配置
- `logback.xml` - 日志配置
- 环境变量: `JAVA_OPTS`, `SPRING_PROFILES_ACTIVE`

---

## 📚 相关文档

- [MODULE_NOTE.md](./MODULE_NOTE.md) - 从 z-opc 拆分说明
- [z-opc 父项目](https://github.com/yuku123/z-opc) - 完整源码

---

## 📝 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-09-06 | 从 z-opc monorepo 拆分独立仓, 文档补齐 |

---

## 📄 License

Internal use only. 版权属于 z-biz。

_Maintained by z-opc-foundation organization._
