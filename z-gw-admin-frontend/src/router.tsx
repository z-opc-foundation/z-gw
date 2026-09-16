import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Layout } from './components/Layout'
import { RoutesListPage } from './pages/Routes/List'
import { RouteEditPage } from './pages/Routes/Edit'
import { MetaPage } from './pages/Meta'
import { MetricsPage } from './pages/Metrics'
import { OverviewPage } from './pages/Overview'

/**
 * 路由表 — SPA 路径配置。
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/overview" replace /> },
      { path: 'overview', element: <OverviewPage /> },
      { path: 'routes', element: <RoutesListPage /> },
      { path: 'routes/new', element: <RouteEditPage /> },
      { path: 'routes/:id', element: <RouteEditPage /> },
      { path: 'meta', element: <MetaPage /> },
      { path: 'metrics', element: <MetricsPage /> },
    ],
  },
])
