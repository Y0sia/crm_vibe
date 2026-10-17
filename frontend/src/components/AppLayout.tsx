import { useState } from 'react'
import { Link, NavLink, Outlet, useMatches } from 'react-router'
import { useLogout, useMe, useNewCount } from '../api/hooks'
import { initials } from '../lib/format'
import { useLiveUpdates } from '../realtime/useLiveUpdates'
import { Icon, type IconName } from './Icon'

type NavItem = { to: string; label: string; icon: IconName; badge?: number }

const ROLE_LABEL = { ADMIN: 'Администратор', MANAGER: 'Менеджер' } as const

function NavLinks({ items, onNavigate, iconSize }: { items: NavItem[]; onNavigate?: () => void; iconSize: number }) {
  return items.map((item) => (
    <NavLink key={item.to} to={item.to} onClick={onNavigate} className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
      <Icon name={item.icon} size={iconSize} />
      {item.label}
      {item.badge ? <span className="nav-badge num">{item.badge}</span> : null}
    </NavLink>
  ))
}

export function AppLayout() {
  const me = useMe().data
  const newCount = useNewCount().data?.count ?? 0
  const logout = useLogout()
  const [menuOpen, setMenuOpen] = useState(false)
  const matches = useMatches()
  const title = [...matches].reverse().map((m) => (m.handle as { title?: string } | undefined)?.title).find(Boolean)

  useLiveUpdates(!!me)
  // при выходе пользователь становится null чуть раньше, чем срабатывает переход на вход
  if (!me) return null

  const main: NavItem[] = [
    { to: '/dashboard', label: 'Дашборд', icon: 'chart' },
    { to: '/leads', label: 'Лиды', icon: 'users', badge: newCount },
  ]
  const settings: NavItem[] =
    me.role === 'ADMIN'
      ? [
          { to: '/settings/statuses', label: 'Статусы', icon: 'list' },
          { to: '/settings/tags', label: 'Теги', icon: 'tag' },
          { to: '/settings/users', label: 'Пользователи', icon: 'user' },
        ]
      : []

  return (
    <div className="app">
      <nav className="sidebar" aria-label="Основное меню">
        <Link to="/dashboard" className="brand" style={{ color: 'inherit', textDecoration: 'none' }}>
          <span className="brand-mark">
            <Icon name="logo" />
          </span>
          CRM Leads
        </Link>
        <div className="nav-group">
          <NavLinks items={main} iconSize={18} />
        </div>
        {settings.length > 0 && (
          <div className="nav-group">
            <div className="nav-heading">Настройки</div>
            <NavLinks items={settings} iconSize={18} />
          </div>
        )}
        <div className="nav-user">
          <div className="avatar" aria-hidden="true" style={{ width: 36, height: 36, background: '#3A414D', fontSize: 13 }}>
            {initials(me.displayName)}
          </div>
          <div className="nav-user-name">
            <b>{me.displayName}</b>
            <span>{ROLE_LABEL[me.role]}</span>
          </div>
          <button type="button" className="btn-logout" onClick={() => logout.mutate()}>
            <Icon name="logout" size={15} />
            Выйти
          </button>
        </div>
      </nav>

      <header className="mobile-header">
        <h1>{title ?? 'CRM Leads'}</h1>
        <button type="button" className="icon-square" aria-label="Открыть меню" onClick={() => setMenuOpen(true)}>
          <Icon name="menu" size={22} />
        </button>
      </header>

      {menuOpen && (
        <div className="mobile-menu" role="dialog" aria-modal="true" aria-label="Меню">
          <header>
            <span className="brand" style={{ padding: 0 }}>
              <span className="brand-mark">
                <Icon name="logo" />
              </span>
              CRM Leads
            </span>
            <button type="button" className="icon-square" aria-label="Закрыть меню" onClick={() => setMenuOpen(false)}>
              <Icon name="close" size={20} />
            </button>
          </header>
          <nav aria-label="Основное меню">
            <NavLinks items={main} iconSize={24} onNavigate={() => setMenuOpen(false)} />
            {settings.length > 0 && (
              <>
                <div className="nav-heading">Настройки</div>
                <NavLinks items={settings} iconSize={24} onNavigate={() => setMenuOpen(false)} />
              </>
            )}
          </nav>
          <footer>
            <div className="muted" style={{ color: '#A4AAB5', marginBottom: 12, fontSize: 14 }}>
              {me.displayName} · {ROLE_LABEL[me.role]}
            </div>
            <button type="button" className="btn-logout" onClick={() => logout.mutate()}>
              <Icon name="logout" size={18} />
              Выйти
            </button>
          </footer>
        </div>
      )}

      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
