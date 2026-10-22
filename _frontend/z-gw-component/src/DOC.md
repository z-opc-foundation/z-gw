# z-gw-component

z-gw（API 网关）跨仓唯一前端面：把「网关实例自省 / 在线路由清单 / 路由详情 / 网关指标」
这一组网关运维视图收敛成一个可复用的 React 组件包，由 `z-gw-suit` 独立壳、z-team 的
OpsWorkbench（`/gw/*` 通配挂载）或 z-opc 主壳挂载。

- **npm 包名**：`@yuku123/z-gw-component`
- **宿主接入**：`@yuku123/z-gw-component/pages`（manifest + 页面组件），主壳可只取 `ComponentDocPage`
- **后端**：z-opc 侧的 `GwProxyController`（`GET /api/gw/**` → 同 JVM 环回 → z-gw-admin 的
  `/gw/admin/**`），即 z-gw-admin 的原生应答
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

包里有**两套**页面入口，路由前缀不同，别混：

### 1.1 manifest 面（`/z-gw/*`，`src/pages-manifest.jsx`）

| 页面 | 路由（相对 `/z-gw/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |
| 实例与端口 | `instance` | `pages/Instance.jsx` | `/gw/__instance` 自省三态（管理面接没接 / Netty 在不在 / 指标桥可见否）+ 谓词字典 |
| 路由清单 | `routes` | `pages/RouteList.jsx` | 路由表 + 统计卡（总数 / 启用 / 停用） |
| 指标 | `metrics` | `pages/Metrics.jsx` | 请求计数与平均 RT + 指标桥探针 |
| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-gw/home` |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

### 1.2 GwApp 面（`/gw/*`，`src/pages/GwApp.jsx`）

`GwApp` 是给 OpsWorkbench 通配挂载用的**子路由集合**，入口重定向到 `routes`：

| 子路由 | 源文件 | 能力 |
|--------|--------|------|
| `routes` | `pages/RouteList.jsx` | 同上（复用同一个组件） |
| `routes/:id` | `pages/RouteDetail.jsx` | 单条路由的谓词 / 过滤器 / metadata |
| `metrics` | `pages/Metrics.jsx` | 同上（复用同一个组件） |
| `instance` | `pages/GwInstance.jsx` | 与 `pages/Instance.jsx` **功能重叠的第二份实现**（见已知限制） |

## 二、导出面

```js
// 主入口（. 桶）：数据源 + GwApp 挂载点
import {gwApi, gwErrorText, predicateText, parseSummaryRows, GwApp} from '@yuku123/z-gw-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件
import {menuItems, routes, HomePage, LoginPage, ComponentDocPage} from '@yuku123/z-gw-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-gw-component'
```

## 三、API 端点

请求实例是基座的默认 `request`（`@yuku123/z-frontend-common` 的 `createRequest()` 预建实例，
`baseURL` 默认 `/api`），token 走 `Authorization: Bearer`。
下表路径是 `src/services/api.js` 里 `gwApi` 定义的**实际路径**（拼上 baseURL 即 `/api/gw/**`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/gw/__instance` | z-opc 侧自省，不经过 z-gw-admin（拆「管理面接没接 / Netty 在不在 / 指标桥可见否」） |
| GET | `/api/gw/routes` | 路由清单（`[{id, uri, order, enabled, predicates, filters, metadata}]`） |
| GET | `/api/gw/routes/{id}` | 单条路由详情（上游 404 是空体，proxy 会补 JSON 错误体） |
| GET | `/api/gw/routes/stats` | 路由统计 `{total, enabled, disabled}` |
| GET | `/api/gw/metrics/summary` | 指标汇总 `{totalRequests, "rt:<route>:<status>", "count:<route>:<status>"}` |
| GET | `/api/gw/metrics/routes` | 按路由聚合 `{<route>: {requests: n}}` |
| GET | `/api/gw/meta/status` | 网关 started / 路由数（⚠ 已定义但**当前没有页面调用**） |
| GET | `/api/gw/meta/predicates` | 谓词字典（网关认识哪些谓词工厂） |

## 四、页面说明

### 4.1 实例与端口（`pages/Instance.jsx`）

把三件容易混为一谈的事拆开：

1. **管理面**（8888 的 `/gw/admin`）：`adminSurface.status === 200` 算已接通；
2. **Netty 数据面**（9090）：`nettyAcceptingNow === true` 算本 JVM 持有；
3. **指标桥**（Micrometer）：`micrometerBridge.bridgeVisible` 决定「指标页的 0」是不是"真没有流量"。

顶部三张 `Statistic` 卡分别给这三个结论，下面 `Descriptions` 铺开自省返回的其余字段，
最后一张卡是谓词字典（读不到就显示「未取得」）。

### 4.2 路由清单（`pages/RouteList.jsx`）

三张统计卡（总数 / 启用 / 停用，走独立的 `/routes/stats`）+ 路由表。
表列：路由 ID（可点进详情）、目标 URI、`order`、启用状态、谓词（AND，折叠成 `Path=/api/**` 形式）、
过滤器、metadata 项数。统计卡失败**不拖垮主表**，单独 catch 后显示 `-`。
列表接口返回的不是数组时直接抛错（提示「路由列表字段不是数组」），不静默渲染空表。

### 4.3 路由详情（`pages/RouteDetail.jsx`）

单条 `RouteDefinition`：基本字段 + 谓词表（工厂 / 展开 / args）+ 过滤器表 + metadata 标签。
404 与 502 的含义在错误提示里区分写清（id 不在表里 / 上游面未接通 / 环回转发失败）。

### 4.4 指标（`pages/Metrics.jsx`）

`totalRequests` 统计 + 按 `status` 展开的 RT / 计数表 + 按路由维度的原始 JSON。
⚠ 当 `totalRequests=0` 且指标桥探针不通时，页面自己挂一条 warning：
两本账（Micrometer 静态 `globalRegistry` vs Spring 容器 `MeterRegistry`）没打通，
此时的 0 **不能**读成「网关没有流量」。

### 4.5 登录

当前是 **mock 模式**：比对一对写死在 `src/pages/LoginPage.jsx` 里的管理员凭据
（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，
再 `window.location.href = '/z-gw/home'`。CTC/SSO 尚未接入。

### 4.6 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 请求实例 | 基座默认 `request` | `baseURL` 默认 `/api`，超时 10000ms，token 走 `Authorization: Bearer`（读 `localStorage.token`） |
| `gwApi` 路径前缀 | `/gw/**` | 拼上 baseURL 后即 `/api/gw/**` |
| 响应解包 | `{code, data, message}` | 基座统一解包：`code !== 200` 即 reject，裸数据原样透传 |

## 六、已知限制

- **两套入口、两份实例页**：`pages/Instance.jsx`（manifest 的 `/z-gw/instance`）与
  `pages/GwInstance.jsx`（GwApp 的 `/gw/instance`）**功能重叠**，是同一个 `/gw/__instance`
  自省数据的两份实现（后者更详细，带 alert 解释根因）。改一处不会同步到另一处。
- **详情页只在 `/gw/*` 下可达**：路由清单里点路由 ID 跳的是写死的 `/gw/routes/{id}`，
  详情页「返回列表」也写死 `/gw/routes`。⇒ 挂在 `/z-gw/*` 前缀下时点不进详情页；
  只有 OpsWorkbench 的 `/gw/*` 通配挂载能走通详情。
- manifest 里 `import RouteDetail` / `import GwApp` 但**既没进 `routes` 也没再导出**
  ⇒ `RouteDetail` 只能经 `GwApp` 的子路由到达，`GwApp` 只能从主入口 `.` 桶拿。
- `gwApi.metaStatus`（`GET /api/gw/meta/status`）已定义但**没有任何页面调用**。
- **上游依赖没接通**：`z-gw-admin` 从未进 main-starter 的 pom ⇒ 8888 的 `/actuator/mappings`
  里 `/gw/admin` 是 0 条。除 `/gw/__instance` 外，其余端点典型表现是 502
  "answered non-JSON"（被 SPA fallback 接走）或 404 透传 —— **不是「网关没有路由」**。
- **指标两本账没打通**：`MetricsGlobalFilter` 记到 Micrometer 静态 `globalRegistry`，
  `MetricsController` 注入的是 Spring 容器 `MeterRegistry` ⇒ 2026-09-24 实测
  `/actuator/metrics/zgw.request.total` 返回 404。
- 静态 `message`（antd 6 下会告警，规范要求改 `App.useApp()`）。
- z-gw 已被 z-opc 主壳的 `domainRoutes.ts` 与 z-team 的 OpsWorkbench 引用
  ⇒ `/z-gw/docs` 在平台主壳里**可达**（主壳按 manifest 的 `routes` 数组挂载）。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.1 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.1 | 首版：实例自省 / 路由清单 / 路由详情 / 指标页面集 + manifest + GwApp 挂载点 |
