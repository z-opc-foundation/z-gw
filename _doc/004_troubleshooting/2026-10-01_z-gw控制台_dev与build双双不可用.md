# 2026-10-01 · z-gw 控制台：一个 import 路径写错导致 dev/build 双双不可用（代码未动）

登记时间：**10-01 14:54**（`date` 现读 14:54:12 CST）。量在提交树 `z-gw main = 7bbbcee`。
量具在 `~/.cache/zopc_ui_sweep/sweep/`：`sweep_console.js` + `run_zgw_sweep.sh`，
账本 `click_zgw.jsonl`（md5 `9293fae7f63ebfd4ccfe12e5173551ac`，7 行）。
依赖与构建都跑在仓外副本 `~/.cache/zopc_ui_sweep/consoles/z-gw-admin-frontend`
（该模块 `node_modules` 不在 z-gw 的 gitignore 里，仓库里装会留下上万条未跟踪文件）。

## 一句话

`src/pages/Routes/List.tsx:14` 写成 `from '../api/client'`（少一层），
而 `List.tsx` 被 `src/router.tsx` 静态 import ⇒ **vite dev 5 条路由全白屏、`vite build` 直接 rc=1**，
这个控制台从来没有跑起来过。另外 `tsc -b` 还有两支工程引用错误，比这个更早挡下来。

## 实测

### 1. dev：5/5 路由 `#root.children = 0`，控件数 0

```
$ grep '"ev":"RENDER"' click_zgw.jsonl
/overview  /routes  /routes/new  /meta  /metrics   ← 每条都是
  controls:0 rows:0 textLen:0 rootChildren:0  bad:["500 .../src/pages/Routes/List.tsx"]
```
vite 侧原文（`vite_zgw.log`）：

```
Pre-transform error: Failed to resolve import "../api/client" from "src/pages/Routes/List.tsx". Does the file exist?
File: .../z-gw-admin-frontend/src/pages/Routes/List.tsx:14:47
  31 |  import { routeApi } from "../api/client";
```

### 2. build：rc=1，同一条原因

```
$ node_modules/.bin/vite build      →  VITE BUILD rc=1
Could not resolve "../api/client" from "src/pages/Routes/List.tsx"
```

### 3. `npm run build` 更早地红在 tsconfig（rc=2，两支 TS 错，跟源码无关）

```
$ npm run build                     →  BUILD rc=2
tsconfig.json(25,18): error TS6306: Referenced project '.../tsconfig.node.json' must have setting "composite": true.
tsconfig.json(25,18): error TS6310: Referenced project '.../tsconfig.node.json' may not disable emit.
```
`tsconfig.json:25` 是 `"references": [{ "path": "./tsconfig.node.json" }]`，
而 `tsconfig.node.json` 里没有 `"composite": true` 且写了 `"noEmit": true` —— 两条都是 project reference 的硬性要求。

### 4. 只有一个 import 是错的（同目录邻居都对，说明是手滑不是约定）

```
src/pages/Meta.tsx:3      '../api/client'     ← 对（在 src/pages 下）
src/pages/Overview.tsx:4  '../api/client'     ← 对
src/pages/Metrics.tsx:3   '../api/client'     ← 对
src/pages/Routes/Edit.tsx:17  '../../api/client'  ← 对（在 src/pages/Routes 下）
src/pages/Routes/List.tsx:14  '../api/client'     ← 错，少一层
```
真文件在 `src/api/client.ts`；`vite.config.ts` 与 `tsconfig.json` 都配了 `@ → src` 别名。

## 顺带量到的两件（不是本次修复项）

- `client.ts:31` 的 `baseURL = '/gw/admin'`，`vite.config.ts` 把 `/gw` 代理到 `:8888`，
  而 `:8888` 是 z-opc 合并 JVM：`/gw/admin/routes` → **302**（被 SSO 拦截器吞，302 对"有没有 handler"零信息，
  这条已在 `z-oss/_doc/004_troubleshooting/2026-10-01_z-oss控制台_三处契约断裂.md` 的口径里证过），
  `/gw/__absent__` → **200 text/html**（SPA index 兜底）。⇒ 网关 admin 后端不在这个进程里，
  修好 import 之后这个控制台仍然没有可调的后端，需要单独确认它该连谁。
- `package.json` 的 devDependencies 里同时挂着 `unplugin-vue-components`（Vue 插件，React 项目用不上），
  与"从来没人构建过"互相印证。

## 建议（**代码未动**，等拍板）

1. `src/pages/Routes/List.tsx:14` 改 `'../../api/client'`，或统一成 `'@/api/client'`（别名两边都已配）。
2. `tsconfig.node.json` 加 `"composite": true` 并去掉 `"noEmit": true`（或改用 `"emitDeclarationOnly": false` + 独立 outDir），
   让 `tsc -b` 能过；否则 `npm run build` 永远红在第 1 步之前。
3. 给该前端加一条最小 CI：`npm run build` 必须 rc=0 —— 本次两条错任何一条都能被它挡住。
