/**
 * z-gw (API 网关) 管理面数据源。
 *
 * 后端 z-gw-admin 暴露 /gw/admin/{routes,metrics,meta} 面。直连 z-gw-admin 的
 * 端口与代理形态可能并存，这里走单一 /gw 前缀（由宿主 vite/nginx 反代到 z-gw-admin）。
 * baseURL 默认 ''，configureGw({apiBase}) 参数化。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zgw_token'})

export default request

// 前缀参数注入（lead 005 section 9.3）：默认 ''（路径自带 /gw 前缀）。
export function configureGw(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

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

/** 谓词参数里的单值键固定是 "_genkey_0"：列表页把它折叠成人读的 "Path=/api/**"。 */
export function predicateText(p) {
    if (!p || !p.name) return '-'
    const args = p.args || {}
    const vals = Object.keys(args).map((k) => (k === '_genkey_0' ? args[k] : `${k}=${args[k]}`))
    return vals.length ? `${p.name}=${vals.join(',')}` : p.name
}
