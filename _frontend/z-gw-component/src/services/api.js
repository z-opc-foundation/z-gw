import request from '@/common'

/**
 * z-gw (API 网关) 管理面数据源。
 *
 * 后端形态（2026-09-24 实测）：z-gw-admin 是普通 Spring MVC @RestController（/gw/admin/{routes,metrics,meta}），
 * 但 io.github.yuku123:z-gw-admin 从未进 main-starter 的 pom ⇒ 8888 的 /actuator/mappings 里 /gw/admin 是 0 条。
 * 页面一律走 z-opc 自己的 GwProxyController：GET /api/gw/** →（同 JVM 环回）→ GET /gw/admin/**，
 * 目的是把这条面搬进 /api 的鉴权 + vite 单代理语义里。响应体就是 z-gw-admin 自己产出的 JSON，逐字段口径：
 *   GET /gw/routes            → [{id, uri, order, enabled, predicates:[{name,args}], filters:[{name,args}], metadata:{}}]
 *   GET /gw/routes/{id}       → 单个 RouteDefinition；不存在时上游回 404（空体，proxy 会补 JSON 错误体）
 *   GET /gw/routes/stats      → {total, enabled, disabled}
 *   GET /gw/metrics/summary   → {totalRequests, "rt:<route>:<status>": ms, "count:<route>:<status>": n}
 *   GET /gw/metrics/routes    → {<route>: {requests: n}}
 *   GET /gw/meta/status       → {started, routes}
 *   GET /gw/meta/predicates   → [{name: "Path"}, …]
 *   GET /gw/__instance        → z-opc 侧自省（不经过上游 controller）
 *
 * proxy 自身的错误体是 {status:"error", source:"z-opc-gw-proxy", message, path}；
 * 上游未接通时典型表现：502 "answered non-JSON"（/gw/** 被 SPA fallback 接走）或 404 透传 —— 两者都不是"网关没有路由"。
 */
export const gwApi = {
    instance: () => request.get('/gw/__instance'),
    listRoutes: () => request.get('/gw/routes'),
    route: (id) => request.get(`/gw/routes/${encodeURIComponent(id)}`),
    routeStats: () => request.get('/gw/routes/stats'),
    metricsSummary: () => request.get('/gw/metrics/summary'),
    metricsPerRoute: () => request.get('/gw/metrics/routes'),
    metaStatus: () => request.get('/gw/meta/status'),
    metaPredicates: () => request.get('/gw/meta/predicates'),
}

/** 后端错误体 {status:"error", message, source} / Boot 默认错误体 {error, path} 两种都要能读出话 */
export function gwErrorText(e) {
    const data = e?.response?.data
    if (data && typeof data === 'object') {
        const message = data.message || data.error
        const source = data.source ? ` (${data.source})` : ''
        if (message) return `${message}${source}`
    }
    if (typeof data === 'string' && data.trim()) return data.slice(0, 300)
    return e?.message || String(e)
}

/**
 * 谓词参数里的单值键固定是 "_genkey_0"：PredicateDefinition.of(name, singleArg) 就是这么存的
 * （z-gw-api 源码，RouteAdminController 返回的就是这个形状），列表页把它折叠成人读的 "Path=/api/**"。
 */
export function predicateText(p) {
    if (!p || !p.name) return '-'
    const args = p.args || {}
    const vals = Object.keys(args).map((k) => (k === '_genkey_0' ? args[k] : `${k}=${args[k]}`))
    return vals.length ? `${p.name}=${vals.join(',')}` : p.name
}

/** metrics/summary 的动态键是 rt:/count: 前缀 + "<route>:<status>"，解析回结构化行给表格用 */
export function parseSummaryRows(summary) {
    const rows = new Map()
    Object.keys(summary || {}).forEach((key) => {
        if (key === 'totalRequests') return
        const m = /^(rt|count):(.*)$/.exec(key)
        if (!m) return
        const parts = m[2].split(':')
        const status = parts.pop()
        const route = parts.join(':') || '(未匹配)'
        if (!rows.has(route)) rows.set(route, {route, cells: {}})
        rows.get(route).cells[status] = rows.get(route).cells[status] || {}
        rows.get(route).cells[status][m[1]] = summary[key]
    })
    return Array.from(rows.values())
}
