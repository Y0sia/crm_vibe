import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useMe } from '../api/hooks'

export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe()
  const location = useLocation()
  if (me.isPending) {
    return <div className="center-screen muted">Загрузка…</div>
  }
  if (!me.data) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  return children
}

export function RequireAdmin({ children }: { children: ReactNode }) {
  const me = useMe()
  if (me.data?.role !== 'ADMIN') {
    return <Navigate to="/leads" replace />
  }
  return children
}
