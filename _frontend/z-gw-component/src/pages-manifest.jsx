import { AppstoreOutlined, ClusterOutlined, DashboardOutlined, HomeOutlined } from '@ant-design/icons'
import Instance from './pages/Instance'
import RouteList from './pages/RouteList'
import Metrics from './pages/Metrics'

/** 菜单 + 路由清单（lead 005 §8.2 manifest）。App 壳在 suit/宿主侧组装。 */

export {default as Instance} from './pages/Instance'
export {default as RouteList} from './pages/RouteList'
export {default as Metrics} from './pages/Metrics'
import HomePage from './pages/HomePage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-gw API 网关', short: 'z-gw' }

export const menuItems = [
    { key: '/z-gw/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-gw/instance', label: '实例与端口', icon: <ClusterOutlined /> },
    { key: '/z-gw/routes', label: '路由清单', icon: <AppstoreOutlined /> },
    { key: '/z-gw/metrics', label: '指标', icon: <DashboardOutlined /> },
]

export const routes = [
    { path: '/z-gw/home', Component: HomePage },
    { path: '/z-gw/instance', Component: Instance },
    { path: '/z-gw/routes', Component: RouteList },
    { path: '/z-gw/metrics', Component: Metrics },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
