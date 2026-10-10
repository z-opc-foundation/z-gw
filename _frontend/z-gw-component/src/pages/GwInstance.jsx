import {useCallback, useEffect, useState} from 'react'
import {Alert, Button, Card, Descriptions, Space, Table, Tag} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {gwApi, gwErrorText} from '../services/api'
import {PageHeader} from '@/common/components/ui'

/**
 * 实例与端口 — GET /api/gw/__instance 的可视化（GwProxyController 自省，不经过 z-gw-admin）。
 *
 * 这页把三件容易混为一谈的事拆开：
 *   ① z-gw-admin 的 /gw/admin/** 面在不在 8888（adminSurface.status：200=已接通，404/非 JSON=依赖没接）；
 *   ② 网关 Netty 数据面（9090）是否由本 JVM 持有并可连（nettyAcceptingNow，TCP 探针）；
 *   ③ zgw.* 计数器是否进了 Spring 的 MeterRegistry（micrometerBridge.bridgeVisible，
 *      决定「指标页的 0」是不是"真没有流量"）。
 * 谓词字典（/gw/meta/predicates）也放这页：它是"网关能听懂哪些谓词"的元数据，不是业务数据。
 */
export default function GwInstance() {
    const [data, setData] = useState(null)
    const [predicates, setPredicates] = useState(null)
    const [predError, setPredError] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = useCallback(async () => {
        setLoading(true)
        try {
            setData(await gwApi.instance())
            setError(null)
        } catch (e) {
            setData(null)
            setError(e)
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetch()
    }, [fetch])

    useEffect(() => {
        gwApi.metaPredicates().then((p) => {
            setPredicates(Array.isArray(p) ? p : null)
            setPredError(null)
        }).catch((e) => {
            setPredicates(null)
            setPredError(e)
        })
    }, [data])

    const surface = data?.adminSurface
    const bridge = data?.micrometerBridge
    const wired = surface?.status === 200 && surface?.json

    return (
        <div>
            <PageHeader title="实例与端口"
                        subtitle="GwProxyController 自省接口 GET /api/gw/__instance（admin 面接没接通 / Netty 在不在 / 指标桥不桥）"/>
            <Card loading={loading} extra={
                <Space>
                    <Button icon={<ReloadOutlined/>} loading={loading} onClick={fetch}>刷新</Button>
                </Space>
            }>
                {error ? (
                    <Alert type="error" showIcon message={`自省接口调用失败：${gwErrorText(error)}`}
                           description="/api/gw/__instance 由 z-opc 自己的 GwProxyController 直接应答，不经过 z-gw-admin；它都不通说明是 z-opc 侧的路由/认证问题。"/>
                ) : (
                    <>
                        {!wired && (
                            <Alert type="warning" showIcon style={{marginBottom: 16}}
                                   message={`z-gw-admin 的 /gw/admin/** 面未接通（adminSurface.status=${surface?.status ?? '-'}）`}
                                   description="根因是 bootstraps/z-opc-main-starter/pom.xml 还没有 io.github.yuku123:z-gw-admin 依赖（该构件在本地 m2 有 1.0.1/1.0.2，但从未进 classpath）。接通后本字段应为 200，路由页会立刻出现 ZGwRoutesConfig 注入的 3 条默认路由。"/>
                        )}
                        {bridge && bridge.bridgeVisible === false && (
                            <Alert type="warning" showIcon style={{marginBottom: 16}}
                                   message="micrometerBridge 探针：/actuator/metrics/zgw.request.total 不存在"
                                   description="网关计数器与 Spring MeterRegistry 未打通时，「网关指标」页的 0 只代表「这本账看不到」，不代表 9090 没有流量。"/>
                        )}
                        <Descriptions bordered size="small" column={2}
                                      items={[
                                          {key: 'jvm', label: '应答的 JVM (pid@host)', children: <code>{data?.jvm ?? '-'}</code>},
                                          {
                                              key: 'netty', label: 'Netty 数据面 (9090) 此刻可连',
                                              children: data ? (data.nettyAcceptingNow
                                                  ? <Tag color="success">是（{data.nettyPort}）</Tag>
                                                  : <Tag color="error">否（{data.nettyPort}）</Tag>) : '-'
                                          },
                                          {key: 'localPort', label: '本请求落入的容器端口', children: data?.localPort ?? '-'},
                                          {key: 'zgwEnabled', label: 'zgw.enabled（Spring 解析值）', children: data ? String(data.zgwEnabled) : '-'},
                                          {
                                              key: 'path', label: '转发关系',
                                              children: <code>GET /api/gw/** → 127.0.0.1:&lt;localPort&gt;{`→`}/gw/admin/**</code>
                                          },
                                          {
                                              key: 'surface', label: 'adminSurface.status (/gw/admin/meta/status 环回探针)',
                                              children: surface ? (
                                                  <Space size={4}>
                                                      <Tag color={wired ? 'success' : 'error'}>{String(surface.status)}</Tag>
                                                      {surface.json === false && <Tag color="warning">非 JSON</Tag>}
                                                  </Space>
                                              ) : '-'
                                          },
                                          {
                                              key: 'started', label: '网关 started / 路由数（上游 meta/status 原文）',
                                              children: surface?.body ? (
                                                  <Space size={4}>
                                                      {surface.body.started
                                                          ? <Tag color="success">started</Tag>
                                                          : <Tag color="error">not started</Tag>}
                                                      <span>routes={surface.body.routes}</span>
                                                  </Space>
                                              ) : <span style={{color: '#94a3b8'}}>{surface?.errorBody ? `上游错误体: ${surface.errorBody}` : '-'}</span>
                                          },
                                          {
                                              key: 'bridge', label: 'zgw 计数器在 Spring MeterRegistry 可见',
                                              children: bridge ? (bridge.bridgeVisible
                                                  ? <Tag color="success">可见</Tag>
                                                  : <Tag color="warning">不可见（actuator 状态码 {String(bridge.actualStatus)}）</Tag>) : '-'
                                          },
                                      ]}/>
                    </>
                )}
            </Card>
            <Card title="谓词字典（GET /api/gw/meta/predicates，RouteMatcher 实际认识的谓词工厂）"
                  size="small" style={{marginTop: 12}}>
                {predError ? (
                    <Alert type="info" showIcon
                           message={`谓词字典读取失败：${gwErrorText(predError)}`}
                           description="接通 z-gw-admin 依赖前这必然 404/502；接通后这里列出 Path/Method/Header/Host/Weight/Time 六个内置工厂。"/>
                ) : (
                    <Table size="small" rowKey={(r) => r.name} dataSource={predicates || []}
                           pagination={false} loading={predicates === null && !predError}
                           columns={[{title: '谓词工厂', dataIndex: 'name', key: 'name', render: (v) => <Tag>{v}</Tag>}]}
                           locale={{emptyText: 'meta/predicates 返回 [] —— PredicateFactoryRegistry 里没有任何工厂（内置注册在构造函数里，这不该发生，查启动日志）'}}/>
                )}
            </Card>
        </div>
    )
}
