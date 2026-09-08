import { useEffect, useState } from 'react'
import { Card, Col, Row, Tag, Typography } from 'antd'
import { metaApi } from '../api/client'

/**
 * SPI 元数据展示 — 内置谓词/过滤器工厂字典。
 */
export function MetaPage() {
  const [predicates, setPredicates] = useState<string[]>([])

  useEffect(() => {
    metaApi.predicates().then((list) => setPredicates(list.map((p) => p.name)))
  }, [])

  return (
    <div>
      <Typography.Title level={3}>SPI 元数据</Typography.Title>
      <Row gutter={16}>
        <Col span={12}>
          <Card title="内置谓词工厂 (PredicateFactory)">
            {predicates.length === 0 && <Tag>加载中...</Tag>}
            {predicates.map((p) => (
              <Tag key={p} color="blue" style={{ margin: 4, padding: '4px 10px' }}>
                {p}
              </Tag>
            ))}
          </Card>
        </Col>
        <Col span={12}>
          <Card title="内置过滤器工厂 (GatewayFilterFactory)">
            {[
              'StripPrefix',
              'PrefixPath',
              'RewritePath',
              'AddRequestHeader',
              'AddResponseHeader',
              'RequestRateLimiter',
              'Hystrix',
              'Retry',
            ].map((f) => (
              <Tag key={f} color="purple" style={{ margin: 4, padding: '4px 10px' }}>
                {f}
              </Tag>
            ))}
          </Card>
        </Col>
      </Row>

      <Card style={{ marginTop: 16 }} title="负载均衡算法">
        {['random', 'roundRobin', 'weighted', 'ipHash', 'leastConnections'].map((l) => (
          <Tag key={l} color="green" style={{ margin: 4, padding: '4px 10px' }}>
            {l}
          </Tag>
        ))}
      </Card>

      <Card style={{ marginTop: 16 }} title="限流算法">
        {['tokenBucket', 'slidingWindow', 'fixedWindow'].map((l) => (
          <Tag key={l} color="orange" style={{ margin: 4, padding: '4px 10px' }}>
            {l}
          </Tag>
        ))}
      </Card>

      <Card style={{ marginTop: 16 }} title="设计参考">
        <ul>
          <li><b>Spring Cloud Gateway</b> — Route + Predicate + Filter 模型</li>
          <li><b>Apache ShenYu</b> — SPI 插件扩展、Plugin 数据结构</li>
          <li><b>APISIX</b> — radixtree 路由匹配</li>
          <li><b>Envoy</b> — xDS 配置动态下发思路</li>
          <li><b>Netflix Hystrix</b> — 滑动窗口熔断器</li>
          <li><b>Resilience4j</b> — 限流器 API 风格</li>
        </ul>
      </Card>
    </div>
  )
}
