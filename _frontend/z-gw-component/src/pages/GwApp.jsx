import {Navigate, Route, Routes} from 'react-router-dom'
import RouteList from './RouteList'
import RouteDetail from './RouteDetail'
import Metrics from './Metrics'
import GwInstance from './GwInstance'

/**
 * z-gw (API 网关) 管理面 — OpsWorkbench 以 /gw/* 通配挂进来。
 * 数据一律经 z-opc 的 GwProxyController（GET /api/gw/** → 同 JVM 环回 → /gw/admin/**，即 z-gw-admin 的原生应答）。
 */
export default function GwApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="routes" replace/>}/>
            <Route path="routes" element={<RouteList/>}/>
            <Route path="routes/:id" element={<RouteDetail/>}/>
            <Route path="metrics" element={<Metrics/>}/>
            <Route path="instance" element={<GwInstance/>}/>
        </Routes>
    )
}
