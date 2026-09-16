import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  message,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { routeApi, type RouteDefinition } from '../api/client'

/**
 * 路由列表页 — 展示 + 创建 + 删除 + 跳编辑。
 */
export function RoutesListPage() {
  const navigate = useNavigate()
  const [routes, setRoutes] = useState<RouteDefinition[]>([])
  const [loading, setLoading] = useState(false)

  const load = () => {
    setLoading(true)
    routeApi
      .list()
      .then(setRoutes)
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const onDelete = async (id: string) => {
    await routeApi.remove(id)
    message.success(`路由 ${id} 已删除`)
    load()
  }

  return (
    <Card>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Typography.Title level={3} style={{ margin: 0 }}>路由管理</Typography.Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/routes/new')}
          >
            新建路由
          </Button>
        </Space>
      </div>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={routes}
        pagination={{ pageSize: 20 }}
        columns={[
          {
            title: 'ID',
            dataIndex: 'id',
            width: 200,
            render: (v) => <code>{v}</code>,
          },
          {
            title: '目标 URI',
            dataIndex: 'uri',
            render: (v) => <Tag color="blue">{v}</Tag>,
          },
          {
            title: '顺序',
            dataIndex: 'order',
            width: 80,
          },
          {
            title: '状态',
            dataIndex: 'enabled',
            width: 100,
            render: (v) =>
              v === false ? <Tag color="default">禁用</Tag> : <Tag color="success">启用</Tag>,
          },
          {
            title: '谓词数',
            dataIndex: 'predicates',
            width: 100,
            render: (v?: unknown[]) => (v?.length ?? 0),
          },
          {
            title: '过滤器数',
            dataIndex: 'filters',
            width: 100,
            render: (v?: unknown[]) => (v?.length ?? 0),
          },
          {
            title: '操作',
            width: 200,
            render: (_, r) => (
              <Space>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => navigate(`/routes/${r.id}`)}
                >
                  编辑
                </Button>
                <Popconfirm
                  title="确认删除？"
                  onConfirm={() => onDelete(r.id)}
                  okType="danger"
                >
                  <Button size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  )
}
