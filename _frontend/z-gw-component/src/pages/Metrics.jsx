/** 网关指标页：/gw/metrics/summary + /gw/metrics/routes 汇总为逐路由表格。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Space, Spin, Table, Tag, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {gwApi, gwErrorText} from '../services/api'

const {Title, Paragraph, Text} = Typography

/** metrics/summary 的动态键是 rt:/count: 前缀 + "<route>:<status>"，解析回结构化行 */
function parseSummaryRows(summary) {
    const rows = new Map()
    Object.keys(summary || {}).forEach((key) => {
        if (key === 'totalRequests') return
        const [prefix, rest] = [key.split(':')[0], key.split(':').slice(1).join(':')]
        const [route, status] = rest.split(':')
        if (!route) return
        if (!rows.has(route)) rows.set(route, {route, requests: 0, byStatus: {}})
        const row = rows.get(route)
        if (prefix === 'count') {
            row.requests += summary[key]
            row.byStatus[status] = (row.byStatus[status] || 0) + summary[key]
        } else if (prefix === 'rt') {
            row.avgRt = summary[key]
        }
    })
    return Array.from(rows.values())
}

export default function Metrics() {
    const [summary, setSummary] = useState(null)
    const [perRoute, setPerRoute] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = async () => {
        setLoading(true)
        try {
            const [s, p] = await Promise.all([gwApi.metricsSummary(), gwApi.metricsPerRoute()])
            setSummary(s)
            setPerRoute(p)
            setError(null)
        } catch (e) {
            setError(e)
            setSummary(null)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        fetch()
        const t = setInterval(fetch, 10000)
        return () => clearInterval(t)
    }, [])

    const rows = parseSummaryRows(summary).map(r => ({
        ...r,
        perRoute: perRoute?.[r.route]?.requests,
    }))

    const columns = [
        {title: '路由', dataIndex: 'route', key: 'route', width: 220},
        {title: '请求数 (count)', dataIndex: 'requests', key: 'requests', width: 120},
        {title: '请求数 (per-route)', dataIndex: 'perRoute', key: 'perRoute', width: 130},
        {title: '平均 RT (ms)', dataIndex: 'avgRt', key: 'avgRt', width: 110,
            render: (v) => typeof v === 'number' ? v.toFixed(1) : '—'},
        {title: '状态码分布', key: 'byStatus',
            render: (_, r) => Object.entries(r.byStatus || {}).map(([k, v]) => (
                <Tag key={k} color={k.startsWith('2') ? 'green' : k.startsWith('4') ? 'orange' : k.startsWith('5') ? 'red' : 'default'}>
                    {k}: {v}
                </Tag>
            ))},
    ]

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>网关指标</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
                <Text type="secondary">10s 自动刷新</Text>
            </Space>
            <Paragraph type="secondary">z-gw-admin /gw/metrics/**：totalRequests + rt/count（按 route:status）双口径。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={gwErrorText(error)}/>}

            <Card title={<span>总请求 <Text code>{summary?.totalRequests ?? '—'}</Text></span>} style={{marginBottom: 16}}>
                <Table rowKey="route" dataSource={rows} columns={columns} loading={loading && !rows.length}
                       size="small" pagination={false}/>
            </Card>
        </div>
    )
}
