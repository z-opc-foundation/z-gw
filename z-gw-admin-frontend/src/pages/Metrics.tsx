import { useEffect, useState } from 'react'
import { Card, Col, Empty, Row, Statistic, Tag, Typography } from 'antd'
import { metricsApi } from '../api/client'

/**
 * 运行指标页 — 通过 Micrometer 数据生成简易仪表盘。
 *
 * <p>Prometheus 部署可访问 /actuator/prometheus。
 */
export function MetricsPage() {
  const [summary, setSummary] = useState<Record<string, number>>({})
  const [perRoute, setPerRoute] = useState<Record<string, Record<string, number>>>({})

  const load = () => {
    metricsApi.summary().then(setSummary).catch(() => {})
    metricsApi.perRoute().then(setPerRoute).catch(() => {})
  }

  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [])

  const totalReq = summary.totalRequests ?? 0
  const routeEntries = Object.entries(perRoute)

  return (
    <div>
      <Typography.Title level={3}>运行指标</Typography.Title>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title="总请求数" value={totalReq} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="路由数" value={routeEntries.length} />
          </Card>
        </Col>
        <Col span={12}>
          <Card>
            <Typography.Text type="secondary">
              数据通过 Micrometer 采集,5s 自动刷新。
              Prometheus 抓取请配置 <code>/actuator/prometheus</code>。
            </Typography.Text>
          </Card>
        </Col>
      </Row>

      <Card title="按路由汇总">
        {routeEntries.length === 0 ? (
          <Empty description="暂无指标数据,请先发起一些请求" />
        ) : (
          routeEntries.map(([route, m]) => (
            <div key={route} style={{ marginBottom: 12, padding: 12, background: '#fafafa', borderRadius: 6 }}>
              <Tag color="blue" style={{ marginRight: 8 }}>
                {route}
              </Tag>
              <Typography.Text strong>{m.requests ?? 0}</Typography.Text>
              <Typography.Text type="secondary"> 次请求</Typography.Text>
            </div>
          ))
        )}
      </Card>
    </div>
  )
}
