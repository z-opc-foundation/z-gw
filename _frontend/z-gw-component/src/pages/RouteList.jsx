/** 路由列表（/gw/routes）+ 统计。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Col, Row, Space, Spin, Statistic, Switch, Table, Tag, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {gwApi, gwErrorText, predicateText} from '../services/api'

const {Title, Paragraph, Text} = Typography

export default function RouteList() {
    const [routes, setRoutes] = useState([])
    const [stats, setStats] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = async () => {
        setLoading(true)
        try {
            const [rs, st] = await Promise.all([gwApi.listRoutes(), gwApi.routeStats()])
            setRoutes(Array.isArray(rs) ? rs : [])
            setStats(st)
            setError(null)
        } catch (e) {
            setError(e)
            setRoutes([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => { fetch() }, [])

    const columns = [
        {title: 'ID', dataIndex: 'id', key: 'id', width: 180},
        {title: 'URI', dataIndex: 'uri', key: 'uri', ellipsis: true,
            render: (u) => <Text code style={{fontSize: 12}}>{u}</Text>},
        {title: 'order', dataIndex: 'order', key: 'order', width: 80},
        {title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
            render: (v) => v ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>},
        {title: '谓词', key: 'predicates', ellipsis: true,
            render: (_, r) => (r.predicates || []).map(p => predicateText(p)).join(' && ') || '—'},
        {title: '过滤器', key: 'filters', ellipsis: true,
            render: (_, r) => (r.filters || []).length || '—'},
    ]

    return (
        <div>
            <Space style={{marginBottom: 16}}>
                <Title level={4} style={{margin: 0}}>路由清单</Title>
                <Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>
            </Space>
            <Paragraph type="secondary">网关当前生效的 RouteDefinition 清单（z-gw-admin /gw/routes）。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={gwErrorText(error)}/>}

            {stats && (
                <Row gutter={16} style={{marginBottom: 16}}>
                    <Col span={8}><Card><Statistic title="总路由" value={stats.total}/></Card></Col>
                    <Col span={8}><Card><Statistic title="启用" value={stats.enabled} valueStyle={{color: '#3f8600'}}/></Card></Col>
                    <Col span={8}><Card><Statistic title="停用" value={stats.disabled} valueStyle={{color: '#cf1322'}}/></Card></Col>
                </Row>
            )}

            <Table rowKey="id" dataSource={routes} columns={columns} loading={loading && !routes.length}
                   size="small" pagination={false} scroll={{x: 900}}/>
        </div>
    )
}
