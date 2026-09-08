import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, Spin, Tag, Typography } from 'antd'
import { NodeIndexOutlined, ApiOutlined, CheckCircleOutlined } from '@ant-design/icons'
import { metaApi, routeApi } from '../api/client'

/**
 * 总览页 — 网关运行状态 + 关键指标快速一览。
 */
export function OverviewPage() {
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState<{ started: boolean; routes: number } | null>(null)
  const [stats, setStats] = useState<{ total: number; enabled: number; disabled: number } | null>(null)

  useEffect(() => {
    Promise.all([metaApi.status(), routeApi.stats()])
      .then(([s, st]) => {
        setStatus(s)
        setStats(st)
      })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <Spin size="large" />

  return (
    <div>
      <Typography.Title level={3}>总览</Typography.Title>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card className="metric-card">
            <div className="metric-label">网关运行</div>
            <div className="metric-value">
              {status?.started ? (
                <CheckCircleOutlined />
              ) : (
                <Tag color="red">DOWN</Tag>
              )}
              <span style={{ marginLeft: 8, fontSize: 18 }}>
                {status?.started ? 'UP' : 'DOWN'}
              </span>
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="路由总数"
              value={stats?.total ?? 0}
              prefix={<NodeIndexOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已启用"
              value={stats?.enabled ?? 0}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已禁用"
              value={stats?.disabled ?? 0}
              valueStyle={{ color: '#cf1322' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card title="架构组成" extra={<ApiOutlined />}>
            <ul>
              <li><b>z-gw-api</b> — 公开 API + SPI 接口</li>
              <li><b>z-gw-core</b> — Netty 服务器、路由匹配、谓词/过滤器工厂</li>
              <li><b>z-gw-spring-boot-starter</b> — Spring Boot 自动装配</li>
              <li><b>z-gw-admin</b> — 管理 REST API</li>
              <li><b>z-gw-examples</b> — 单进程启动器示例</li>
              <li><b>z-gw-admin-frontend</b> — React 19 + Vite 6 + Ant Design 6 前端</li>
            </ul>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="设计融合">
            <ul>
              <li>🌐 <b>Spring Cloud Gateway</b> — Route + Predicate + Filter 模型</li>
              <li>🧩 <b>Apache ShenYu</b> — SPI 插件扩展</li>
              <li>🌳 <b>APISIX</b> — radixtree 前缀树路由</li>
              <li>🚀 <b>Envoy</b> — xDS 动态配置思路</li>
              <li>🛠️ <b>自研 Netty</b> — 异步非阻塞运行时</li>
              <li>📦 <b>z-util</b> — 自研工具库复用</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
