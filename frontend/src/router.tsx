import { Navigate, createBrowserRouter } from 'react-router'
import { RequireAdmin, RequireAuth } from './auth/RequireAuth'
import { AppLayout } from './components/AppLayout'
import { DashboardPage } from './pages/dashboard/DashboardPage'
import { LeadPage } from './pages/lead/LeadPage'
import { LeadsPage } from './pages/leads/LeadsPage'
import { LoginPage } from './pages/LoginPage'
import { StatusesPage } from './pages/settings/StatusesPage'
import { TagsPage } from './pages/settings/TagsPage'
import { UsersPage } from './pages/settings/UsersPage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage />, handle: { title: 'Дашборд' } },
      { path: 'leads', element: <LeadsPage />, handle: { title: 'Лиды' } },
      { path: 'leads/:id', element: <LeadPage />, handle: { title: 'Карточка лида' } },
      { path: 'settings', element: <Navigate to="/settings/statuses" replace /> },
      {
        path: 'settings/statuses',
        element: (
          <RequireAdmin>
            <StatusesPage />
          </RequireAdmin>
        ),
        handle: { title: 'Статусы' },
      },
      {
        path: 'settings/tags',
        element: (
          <RequireAdmin>
            <TagsPage />
          </RequireAdmin>
        ),
        handle: { title: 'Теги' },
      },
      {
        path: 'settings/users',
        element: (
          <RequireAdmin>
            <UsersPage />
          </RequireAdmin>
        ),
        handle: { title: 'Пользователи' },
      },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
])
