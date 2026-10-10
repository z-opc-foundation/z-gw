import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Row, Table, Tag} from 'antd'
import {ArrowLeftOutlined, ReloadOutlined} from '@ant-design/icons'
import {useNavigate, useParams} from 'react-router-dom'
import {gwApi, gwErrorText, predicateText} from '../services/api'
import {PageHeader} from '@/common/components/ui'

/**
 * 路由详情 — GET /api/gw/routes/{id}（上游按 InMemoryRouteRepository.get(id) 回单条 RouteDefinition）。
 * 上游 404 是 ResponseEntity.notFound() 空体，proxy 会补成 JSON 错误体透出，这里按消息渲染，不猜。
 */
export default function RouteDetail() {
    const {id} = useParams()
    const navigate = useNavigate()
    const [route, setRoute] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        if (!id) return
        setLoading(true)
        try {
            setRoute(await gwApi.route(id))
            setError(null)
        } catch (e) {
            setRoute(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [id])

    useEffect(() => {
        fetch()
    }, [fetch])

    return (
        <div>
            <PageHeader title={`路由详情：${id || '-'}`}
                        subtitle="z-gw 单条 RouteDefinition 的谓词 / 过滤器 / 元数据"
                        extra={<Button icon={<ArrowLeftOutlined/>} onClick={() => navigate('/gw/routes')}>返回列表</Button>}/>
            {error ? (
                <Card>
                    <Alert type="error" showIcon
                           message={`路由读取失败：${gwErrorText(error)}`}
                           description="404 = 该 id 不在路由表里（名字来自列表页的话，说明它在此期间被改过/重载过）；404 且提示 non-JSON = /gw/admin 面未接通被 SPA fallback 接走；502 = 环回转发失败。"/>
                </Card>
            ) : (
                <Row gutter={12}>
                    <Col span={24}>
                        <Card title="基本字段" loading={loading} size="small"
                              extra={<Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>}>
                            <Descriptions size="small" column={2} bordered
                                          items={[
                                              {key: 'id', label: '路由 ID', children: <code>{route?.id ?? '-'}</code>},
                                              {key: 'uri', label: '目标 URI', children: <code>{route?.uri ?? '-'}</code>},
                                              {
                                                  key: 'order', label: 'order（越小越优先）',
                                                  children: route ? route.order : '-'
                                              },
                                              {
                                                  key: 'enabled', label: '启用',
                                                  children: route
                                                      ? (route.enabled ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>)
                                                      : '-'
                                              },
                                          ]}/>
                        </Card>
                    </Col>
                    <Col span={12} style={{marginTop: 12}}>
                        <Card title="谓词（AND 关系，全部命中才匹配）" size="small" loading={loading}>
                            <Table size="small" rowKey={(_, i) => i} dataSource={route?.predicates || []}
                                   pagination={false}
                                   columns={[
                                       {title: '工厂', dataIndex: 'name', key: 'name', width: 110, render: (v) => <Tag>{v}</Tag>},
                                       {title: '展开', key: 'full', render: (_, p) => <code>{predicateText(p)}</code>},
                                       {
                                           title: 'args', dataIndex: 'args', key: 'args',
                                           render: (a) => Object.entries(a || {}).map(([k, v]) => <div key={k}><code>{k}={v}</code></div>)
                                       },
                                   ]}
                                   locale={{emptyText: '该路由没有谓词（任何请求都不匹配）'}}/>
                        </Card>
                    </Col>
                    <Col span={12} style={{marginTop: 12}}>
                        <Card title="路由级过滤器 / metadata" size="small" loading={loading}>
                            <Table size="small" rowKey={(_, i) => i} dataSource={route?.filters || []}
                                   pagination={false}
                                   columns={[
                                       {title: '过滤器', dataIndex: 'name', key: 'name', render: (v) => <Tag>{v}</Tag>},
                                       {
                                           title: 'args', dataIndex: 'args', key: 'args',
                                           render: (a) => JSON.stringify(a || {})
                                       },
                                   ]}
                                   locale={{emptyText: '无路由级过滤器'}}/>
                            <div style={{marginTop: 10}}>
                                {(Object.keys(route?.metadata || {}).length > 0)
                                    ? Object.entries(route.metadata).map(([k, v]) => (
                                        <Tag key={k}>{k}={v}</Tag>
                                    ))
                                    : <span style={{color: '#94a3b8', fontSize: 12}}>metadata 为空</span>}
                            </div>
                        </Card>
                    </Col>
                </Row>
            )}
        </div>
    )
}
