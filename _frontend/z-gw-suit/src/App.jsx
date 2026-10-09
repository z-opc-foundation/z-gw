import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-gw-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/instance" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-gw API 网关" appShort="GW"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
