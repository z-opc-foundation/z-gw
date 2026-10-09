import {
    AppstoreOutlined, DashboardOutlined, ClusterOutlined,
} from '@ant-design/icons'
import Instance from './pages/Instance'
import RouteList from './pages/RouteList'
import Metrics from './pages/Metrics'

/** 菜单 + 路由清单（lead 005 §8.2 manifest）。App 壳在 suit/宿主侧组装。 */
export const menuItems = [
    {key: '/instance', icon: <ClusterOutlined/>, label: '实例与端口'},
    {key: '/routes', icon: <AppstoreOutlined/>, label: '路由清单'},
    {key: '/metrics', icon: <DashboardOutlined/>, label: '指标'},
]

const routeTable = [
    {path: 'instance', Component: Instance},
    {path: 'routes', Component: RouteList},
    {path: 'metrics', Component: Metrics},
]
export {routeTable}
export {default as Instance} from './pages/Instance'
export {default as RouteList} from './pages/RouteList'
export {default as Metrics} from './pages/Metrics'
