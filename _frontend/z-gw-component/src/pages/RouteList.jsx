import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Row, Space, Statistic, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {useNavigate} from 'react-router-dom'
import {gwApi, gwErrorText, predicateText} from '../services/api'
import {EmptyState, PageHeader} from '@/common/components/ui'

/**
 * 路由列表 — GET /api/gw/routes（上游 RouteAdminController#list，直接序列化 InMemoryRouteRepository
 * 里那份 List<RouteDefinition>；z-opc 侧 ZGwRoutesConfig 今天已通过 SmartInitializingSingleton 注入了
 * opc-api / opc-doc / opc-openapi 三条路由，所以接通后这里应当看到 3 行真数据，而不是空表）。
 * 统计卡走独立的 GET /routes/stats（后端自己再数一遍，两个口径对不上就是仓库有并发问题，页面不替后端圆）。
 */
export default function RouteList() {
    const navigate = useNavigate()
    const [rows, setRows] = useState([])
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            const data = await gwApi.listRoutes()
            if (!Array.isArray(data)) {
                throw new Error(`路由列表字段不是数组，拿到的是 ${JSON.stringify(data)}`)
            }
            setRows(data)
            setError(null)
            // stats 失败不拖垮主表：单独 catch，显示为 "-"
            gwApi.routeStats().then(setStats).catch(() => setStats(null))
        } catch (e) {
            setRows([])
            setStats(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    return (
        <div>
            <PageHeader title="网关路由"
                        subtitle="z-gw 在线路由表（数据源 GET /api/gw/routes → 环回转发到 z-gw-admin 的 /gw/admin/routes）"/>
            <Row gutter={12} style={{marginBottom: 12}}>
                <Col span={8}>
                    <Card><Statistic title="路由总数 (routes/stats.total)" value={stats?.total ?? '-'}/></Card>
                </Col>
                <Col span={8}>
                    <Card><Statistic title="启用 (enabled)" value={stats?.enabled ?? '-'}/></Card>
                </Col>
                <Col span={8}>
                    <Card><Statistic title="停用 (disabled)" value={stats?.disabled ?? '-'}/></Card>
                </Col>
            </Row>
            <Card extra={
                <Space>
                    <Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>
                </Space>
            }>
                {error ? (
                    <Alert type="error" showIcon
                           message={`路由表读取失败：${gwErrorText(error)}`}
                           description="404/502 = z-gw-admin 的 /gw/admin/** 还没接进 8888（pom 缺 io.github.yuku123:z-gw-admin 依赖），不是「网关没有路由」；先去「实例与端口」页看 adminSurface.status。"/>
                ) : (
                    <Table
                        size="small"
                        rowKey={(r) => r.id}
                        loading={loading}
                        dataSource={rows}
                        pagination={false}
                        columns={[
                            {
                                title: '路由 ID', dataIndex: 'id', key: 'id',
                                render: (v) => <a onClick={() => navigate(`/gw/routes/${encodeURIComponent(v)}`)}>{v}</a>
                            },
                            {title: '目标 URI', dataIndex: 'uri', key: 'uri', render: (v) => <code>{v}</code>},
                            {title: 'order', dataIndex: 'order', key: 'order', width: 80},
                            {
                                title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
                                render: (v) => (v ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>)
                            },
                            {
                                title: '谓词 (AND)', dataIndex: 'predicates', key: 'predicates',
                                render: (ps) => (ps || []).map((p, i) => <Tag key={i}>{predicateText(p)}</Tag>)
                            },
                            {
                                title: '过滤器', dataIndex: 'filters', key: 'filters', width: 90,
                                render: (fs) => (fs || []).length ? (fs || []).map((f, i) => <Tag key={i}>{f.name}</Tag>) : '-'
                            },
                            {
                                title: 'metadata', dataIndex: 'metadata', key: 'metadata', width: 100,
                                render: (m) => `${Object.keys(m || {}).length} 项`
                            },
                        ]}
                        locale={{
                            emptyText: <EmptyState title="当前路由表为空"
                                                   description="GET /api/gw/routes 返回 [] —— 接口是通的，InMemoryRouteRepository 里确实一条路由都没有。注意 z-opc 的 ZGwRoutesConfig 会在启动时注入 3 条默认路由，空表通常意味着 zgw.enabled 不为 true 或注入时机出了问题（看后端日志「[z-gw] routes injected」）"/>
                        }}
                    />
                )}
            </Card>
        </div>
    )
}
