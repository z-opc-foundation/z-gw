/** 网关实例自省 + 谓词元数据。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Descriptions, Row, Space, Spin, Statistic, Tag, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {gwApi, gwErrorText} from '../services/api'

const {Title, Paragraph} = Typography

export default function Instance() {
    const [data, setData] = useState(null)
    const [predicates, setPredicates] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = async () => {
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
    }

    useEffect(() => {
        fetch()
        gwApi.metaPredicates().then(p => setPredicates(Array.isArray(p) ? p : null)).catch(() => setPredicates(null))
    }, [])

    const adminOk = data?.adminSurface?.status === 200
    const nettyOk = data?.nettyAcceptingNow === true

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>实例与端口</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
            </Space>
            <Paragraph type="secondary">z-gw-admin 的 /gw/__instance 自省（不经过 z-gw-admin）：拆开"管理面是否接通"/"Netty 数据面是否在本 JVM"/"指标桥是否可见"三件事。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={gwErrorText(error)}/>}
            {loading && !data && <Spin/>}

            {data && (
                <Row gutter={16} style={{marginBottom: 16}}>
                    <Col span={8}>
                        <Card>
                            <Statistic title="管理面 (8888 /gw/admin)"
                                       value={adminOk ? '已接通' : `未接通 (${data?.adminSurface?.status ?? '—'})`}
                                       valueStyle={{color: adminOk ? '#3f8600' : '#cf1322'}}/>
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Card>
                            <Statistic title="Netty 数据面 (9090)"
                                       value={nettyOk ? '本 JVM 持有' : '未持有 / 不可连'}
                                       valueStyle={{color: nettyOk ? '#3f8600' : '#cf1322'}}/>
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Card>
                            <Statistic title="指标桥 (Micrometer)"
                                       value={data?.micrometerBridge?.bridgeVisible ? '可见' : '不可见'}
                                       valueStyle={{color: data?.micrometerBridge?.bridgeVisible ? '#3f8600' : '#faad14'}}/>
                        </Card>
                    </Col>
                </Row>
            )}

            {data && (
                <Card title="实例详情" style={{marginBottom: 16}}>
                    <Descriptions column={2} bordered size="small">
                        {Object.entries(data).filter(([k]) => !['adminSurface', 'nettyAcceptingNow', 'micrometerBridge'].includes(k))
                            .map(([k, v]) => (
                                <Descriptions.Item key={k} label={k}>
                                    {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                                </Descriptions.Item>
                            ))}
                    </Descriptions>
                </Card>
            )}

            <Card title="谓词字典（/gw/meta/predicates）">
                {predicates ? (
                    <Space size="small" wrap>
                        {predicates.map(p => <Tag key={p.name || p} color="blue">{p.name || p}</Tag>)}
                    </Space>
                ) : <Paragraph type="secondary">未取得（后端未连接或非数组响应）</Paragraph>}
            </Card>
        </div>
    )
}
