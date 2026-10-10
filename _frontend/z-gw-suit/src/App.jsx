import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-gw-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/instance" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-gw API 网关" appShort="GW" appIcon={{icon: <img src="/icon.png" alt="GW" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#2563eb', label: 'GW'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
