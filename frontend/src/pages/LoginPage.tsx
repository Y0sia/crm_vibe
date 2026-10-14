import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { useLogin, useMe } from '../api/hooks'
import { Icon } from '../components/Icon'

export function LoginPage() {
  const me = useMe()
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState({ login: '', password: '' })
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  if (me.data) return <Navigate to={from} replace />

  const submit = (e: FormEvent) => {
    e.preventDefault()
    login.mutate(form, { onSuccess: () => navigate(from, { replace: true }) })
  }

  const error = login.error instanceof ApiError ? login.error : null

  return (
    <div className="center-screen">
      <form className="card" onSubmit={submit} style={{ width: 400, maxWidth: '100%', padding: 28, gap: 20 }} noValidate>
        <div className="brand" style={{ padding: 0 }}>
          <span className="brand-mark">
            <Icon name="logo" />
          </span>
          Лиды CRM
        </div>
        <div>
          <h1 className="page-title" style={{ fontSize: 24 }}>
            Вход
          </h1>
          <div className="page-subtitle">Логин и пароль выдаёт администратор</div>
        </div>
        {error && !Object.keys(error.fields).length && <div className="form-error">{error.message}</div>}
        <label className="field">
          Логин
          <input
            className={`input${error?.fields.login ? ' invalid' : ''}`}
            autoComplete="username"
            autoFocus
            value={form.login}
            onChange={(e) => setForm({ ...form, login: e.target.value })}
          />
          {error?.fields.login && <span className="field-error">{error.fields.login}</span>}
        </label>
        <label className="field">
          Пароль
          <input
            className={`input${error?.fields.password ? ' invalid' : ''}`}
            type="password"
            autoComplete="current-password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
          />
          {error?.fields.password && <span className="field-error">{error.fields.password}</span>}
        </label>
        <button type="submit" className="btn btn-primary" disabled={login.isPending}>
          {login.isPending ? 'Входим…' : 'Войти'}
        </button>
      </form>
    </div>
  )
}
