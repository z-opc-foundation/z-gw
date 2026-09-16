import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Layout as AntLayout, Menu, Typography, Tag } from 'antd'
import {
  DashboardOutlined,
  NodeIndexOutlined,
  SettingOutlined,
  LineChartOutlined,
  ApiOutlined,
} from '@ant-design/icons'

const { Header, Sider, Content } = AntLayout

const MENU_ITEMS = [
  { key: '/overview', icon: <DashboardOutlined />, label: '总览' },
  { key: '/routes', icon: <NodeIndexOutlined />, label: '路由管理' },
  { key: '/metrics', icon: <LineChartOutlined />, label: '运行指标' },
  { key: '/meta', icon: <ApiOutlined />, label: 'SPI 元数据' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统设置' },
]

/**
 * 全局布局 — 左侧菜单 + 顶部 logo + 主体内容。
 */
export function Layout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        theme="dark"
        width={220}
      >
        <div
          style={{
            color: '#fff',
            padding: '16px',
            textAlign: 'center',
            fontSize: collapsed ? 16 : 18,
            fontWeight: 600,
            letterSpacing: 1,
          }}
        >
          {collapsed ? 'z-gw' : '⚡ z-gw 网关'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname.startsWith('/routes') ? '/routes' : location.pathname]}
          items={MENU_ITEMS}
          onClick={(e) => navigate(e.key)}
        />
      </Sider>
      <AntLayout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            boxShadow: '0 1px 4px rgba(0,21,41,.08)',
          }}
        >
          <Typography.Title level={4} style={{ margin: 0 }}>
            API 网关管理面板
          </Typography.Title>
          <div>
            <Tag color="success">v1.0.0</Tag>
            <Tag color="blue">Spring Boot 2.7.18</Tag>
            <Tag color="purple">Netty 4.1.100</Tag>
          </div>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8 }}>
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  )
}
