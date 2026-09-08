import axios, { AxiosInstance } from 'axios'
import { message } from 'antd'

/**
 * 路由定义 — 与后端 {@code com.zifang.z.gw.api.RouteDefinition} 一一对应。
 */
export interface PredicateDefinition {
  name: string
  args: Record<string, string>
}

export interface FilterDefinition {
  name: string
  args: Record<string, string>
}

export interface RouteDefinition {
  id: string
  uri: string
  order?: number
  enabled?: boolean
  predicates?: PredicateDefinition[]
  filters?: FilterDefinition[]
  metadata?: Record<string, string>
}

/**
 * Axios 实例 — 网关 admin API 客户端。
 */
export const http: AxiosInstance = axios.create({
  baseURL: '/gw/admin',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

http.interceptors.response.use(
  (r) => r,
  (err) => {
    const msg =
      err.response?.data?.message || err.message || '请求失败'
    message.error(`网关 admin 调用失败: ${msg}`)
    return Promise.reject(err)
  }
)

/** 路由 API */
export const routeApi = {
  list: () => http.get<RouteDefinition[]>('/routes').then((r) => r.data),
  get: (id: string) =>
    http.get<RouteDefinition>(`/routes/${encodeURIComponent(id)}`).then((r) => r.data),
  create: (route: RouteDefinition) => http.post<RouteDefinition>('/routes', route).then((r) => r.data),
  update: (id: string, route: RouteDefinition) =>
    http.put<RouteDefinition>(`/routes/${encodeURIComponent(id)}`, route).then((r) => r.data),
  remove: (id: string) =>
    http.delete<void>(`/routes/${encodeURIComponent(id)}`).then((r) => r.data),
  reload: (routes: RouteDefinition[]) =>
    http.post<{ count: number }>('/routes/reload', routes).then((r) => r.data),
  stats: () =>
    http.get<{ total: number; enabled: number; disabled: number }>('/routes/stats').then((r) => r.data),
}

/** 元数据 API */
export const metaApi = {
  predicates: () =>
    http.get<{ name: string }[]>('/meta/predicates').then((r) => r.data),
  status: () =>
    http.get<{ started: boolean; routes: number }>('/meta/status').then((r) => r.data),
}

/** 指标 API */
export const metricsApi = {
  summary: () => http.get<Record<string, number>>('/metrics/summary').then((r) => r.data),
  perRoute: () =>
    http.get<Record<string, Record<string, number>>>('/metrics/routes').then((r) => r.data),
}
