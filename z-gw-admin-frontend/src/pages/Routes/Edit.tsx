import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, MinusCircleOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { metaApi, routeApi, type RouteDefinition, type FilterDefinition, type PredicateDefinition } from '../../api/client'

const PREDICATE_OPTIONS = ['Path', 'Method', 'Header', 'Host', 'Weight', 'Time']
const FILTER_OPTIONS = ['StripPrefix', 'PrefixPath', 'RewritePath', 'AddRequestHeader', 'AddResponseHeader', 'RequestRateLimiter', 'Hystrix', 'Retry']

/**
 * 路由编辑页 — 创建/更新路由定义。
 */
export function RouteEditPage() {
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()
  const [form] = Form.useForm<RouteDefinition>()
  const [predicates, setPredicates] = useState<PredicateDefinition[]>([])
  const [filters, setFilters] = useState<FilterDefinition[]>([])
  const [loading, setLoading] = useState(false)
  const [builtInPredicates, setBuiltInPredicates] = useState<string[]>([])

  useEffect(() => {
    metaApi.predicates().then((list) => setBuiltInPredicates(list.map((p) => p.name)))
    if (id) {
      setLoading(true)
      routeApi
        .get(id)
        .then((r) => {
          form.setFieldsValue(r)
          setPredicates(r.predicates ?? [])
          setFilters(r.filters ?? [])
        })
        .finally(() => setLoading(false))
    } else {
      form.setFieldsValue({ enabled: true, order: 0 } as RouteDefinition)
    }
  }, [id, form])

  const onSubmit = async () => {
    try {
      const values = await form.validateFields()
      const payload: RouteDefinition = {
        ...values,
        predicates: predicates.filter((p) => p.name),
        filters: filters.filter((f) => f.name),
      }
      if (id) {
        await routeApi.update(id, payload)
        message.success('路由已更新')
      } else {
        await routeApi.create(payload)
        message.success('路由已创建')
      }
      navigate('/routes')
    } catch (e) {
      console.error(e)
    }
  }

  return (
    <Card loading={loading}>
      <Typography.Title level={3}>{id ? `编辑路由: ${id}` : '新建路由'}</Typography.Title>

      <Form form={form} layout="vertical" style={{ maxWidth: 800 }}>
        <Form.Item name="id" label="路由 ID" rules={[{ required: true, message: '请输入路由 ID' }]}>
          <Input placeholder="例如: user-service" disabled={!!id} />
        </Form.Item>

        <Form.Item
          name="uri"
          label="目标 URI"
          rules={[{ required: true, message: '请输入目标 URI' }]}
          extra="支持 http(s)://host:port、lb://serviceName、forward://localPath"
        >
          <Input placeholder="lb://user-service  或  http://localhost:8081" />
        </Form.Item>

        <Space size="large" style={{ marginBottom: 16 }}>
          <Form.Item name="order" label="顺序" tooltip="数字越小优先级越高">
            <InputNumber min={0} max={10000} />
          </Form.Item>

          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Space>

        {/* 谓词 */}
        <Card type="inner" title="谓词 (AND)" style={{ marginBottom: 16 }}>
          {predicates.map((p, idx) => (
            <Space key={idx} style={{ marginBottom: 8 }} align="baseline">
              <Select
                style={{ width: 200 }}
                value={p.name}
                options={PREDICATE_OPTIONS.map((o) => ({ value: o, label: o }))}
                onChange={(v) => {
                  const np = [...predicates]
                  np[idx] = { ...p, name: v, args: p.args || {} }
                  setPredicates(np)
                }}
              />
              <Input
                style={{ width: 360 }}
                placeholder="args: JSON 格式, 例: {_genkey_0: /api/**}"
                value={JSON.stringify(p.args || {})}
                onChange={(e) => {
                  try {
                    const args = JSON.parse(e.target.value || '{}')
                    const np = [...predicates]
                    np[idx] = { ...p, args }
                    setPredicates(np)
                  } catch {
                    // ignore invalid JSON during typing
                  }
                }}
              />
              <Button
                type="text"
                danger
                icon={<MinusCircleOutlined />}
                onClick={() => setPredicates(predicates.filter((_, i) => i !== idx))}
              />
            </Space>
          ))}
          <Button
            icon={<PlusOutlined />}
            onClick={() =>
              setPredicates([...predicates, { name: 'Path', args: { _genkey_0: '/**' } }])
            }
          >
            添加谓词
          </Button>
        </Card>

        {/* 过滤器 */}
        <Card type="inner" title="过滤器" style={{ marginBottom: 16 }}>
          {filters.map((f, idx) => (
            <Space key={idx} style={{ marginBottom: 8 }} align="baseline">
              <Select
                style={{ width: 200 }}
                value={f.name}
                options={FILTER_OPTIONS.map((o) => ({ value: o, label: o }))}
                onChange={(v) => {
                  const nf = [...filters]
                  nf[idx] = { ...f, name: v, args: f.args || {} }
                  setFilters(nf)
                }}
              />
              <Input
                style={{ width: 360 }}
                placeholder="args: JSON 格式, 例: {_genkey_0: 1}"
                value={JSON.stringify(f.args || {})}
                onChange={(e) => {
                  try {
                    const args = JSON.parse(e.target.value || '{}')
                    const nf = [...filters]
                    nf[idx] = { ...f, args }
                    setFilters(nf)
                  } catch {
                    // ignore
                  }
                }}
              />
              <Button
                type="text"
                danger
                icon={<MinusCircleOutlined />}
                onClick={() => setFilters(filters.filter((_, i) => i !== idx))}
              />
            </Space>
          ))}
          <Button
            icon={<PlusOutlined />}
            onClick={() => setFilters([...filters, { name: 'StripPrefix', args: { _genkey_0: '1' } }])}
          >
            添加过滤器
          </Button>
        </Card>

        <Space>
          <Button type="primary" onClick={onSubmit}>
            保存
          </Button>
          <Button onClick={() => navigate('/routes')}>取消</Button>
          <Tag color="cyan">已注册谓词: {builtInPredicates.join(', ') || '(加载中...)'}</Tag>
        </Space>
      </Form>
    </Card>
  )
}
