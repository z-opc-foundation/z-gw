import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Space, Statistic, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {gwApi, gwErrorText, parseSummaryRows} from '../services/api'
import {PageHeader} from '@/common/components/ui'

/**
 * 网关指标 — GET /api/gw/metrics/summary + /metrics/routes（上游 MetricsController 聚合
 * Micrometer 的 zgw.request.total / zgw.request.duration，维度是 route × method × status）。
 *
 * ⚠️ 已知风险（交接文档 007.md 有实测记录）：MetricsGlobalFilter 默认往 Micrometer 的
 * 静态 globalRegistry 记数，而 MetricsController 注入的是 Spring 容器里的 MeterRegistry bean。
 * 2026-09-24 在 9090 已真实流过请求的前提下 /actuator/metrics/zgw.request.total=404，
 * 即两本账没打通 —— 所以"本页 totalRequests=0"有两种解释。这里把 __instance 的
 * micrometerBridge.bridgeVisible 探针结果一并渲染，0 值 + bridge 不通时页面自己说明白，
 * 不假装是"没有流量"。
 */
export default function Metrics() {
    const [summary, setSummary] = useState(null)
    const [perRoute, setPerRoute] = useState(null)
    const [bridge, setBridge] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            const s = await gwApi.metricsSummary()
            setSummary(s)
            setError(null)
            gwApi.metricsPerRoute().then(setPerRoute).catch(() => setPerRoute(null))
            gwApi.instance()
                .then((i) => setBridge(i?.micrometerBridge ?? null))
                .catch(() => setBridge(null))
        } catch (e) {
            setSummary(null)
            setPerRoute(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    const statuses = Array.from(new Set(
        parseSummaryRows(summary).flatMap((r) => Object.keys(r.cells))))
    const rows = parseSummaryRows(summary)
    const noTrafficButBridgeDead = !error && summary
        && !(summary.totalRequests > 0) && bridge && bridge.bridgeVisible === false

    return (
        <div>
            <PageHeader title="网关指标"
                        subtitle="z-gw 请求计数与平均 RT（数据源 /api/gw/metrics/summary，Micrometer zgw.* 指标聚合）"/>
            <Card loading={loading} extra={
                <Space>
                    <Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>
                </Space>
            }>
                {error ? (
                    <Alert type="error" showIcon
                           message={`指标读取失败：${gwErrorText(error)}`}
                           description="404/502 = z-gw-admin 未接通（见「实例与端口」页）；本接口不依赖路由表，接通后即有值（哪怕全 0）。"/>
                ) : (
                    <>
                        {noTrafficButBridgeDead && (
                            <Alert type="warning" showIcon style={{marginBottom: 16}}
                                   message="totalRequests=0，但 micrometerBridge 探针显示 /actuator/metrics/zgw.request.total 也不存在"
                                   description="z-gw 的 MetricsGlobalFilter 记到 Micrometer 静态 globalRegistry，MetricsController 注入的是 Spring 容器 MeterRegistry —— 两本账未打通时，这里的 0 不能读成「网关没有流量」。以 9090 数据面日志 / actuator 为准。"/>
                        )}
                        <Statistic title="totalRequests（zgw.request.total 全计数求和）"
                                   value={summary?.totalRequests ?? '-'}/>
                        <Table size="small" style={{marginTop: 16}}
                               rowKey={(r) => r.route}
                               dataSource={rows}
                               pagination={false}
                               columns={[
                                   {title: '路由', dataIndex: 'route', key: 'route', render: (v) => <code>{v}</code>},
                                   ...statuses.map((st) => ({
                                       title: `status=${st}`,
                                       key: st,
                                       render: (_, r) => {
                                           const c = r.cells[st] || {}
                                           return (
                                               <Space size={4}>
                                                   <Tag>RT {typeof c.rt === 'number' ? c.rt.toFixed(2) : '-'} ms</Tag>
                                                   <Tag color="blue">n={c.count ?? '-'}</Tag>
                                               </Space>
                                           )
                                       },
                                   })),
                               ]}
                               locale={{
                                   emptyText: 'summary 里没有任何 rt:/count: 动态键 —— 注入的 MeterRegistry 里还没有 zgw.* 计数器（结合上方 bridge 探针判断是"没流量"还是"没打通"）',
                               }}/>
                        <Card type="inner" title="按路由维度（GET /metrics/routes，只聚合 zgw.request.total 计数器）"
                              size="small" style={{marginTop: 16}}>
                            <pre style={{margin: 0, fontSize: 12}}>{JSON.stringify(perRoute ?? {}, null, 2)}</pre>
                        </Card>
                    </>
                )}
            </Card>
        </div>
    )
}
