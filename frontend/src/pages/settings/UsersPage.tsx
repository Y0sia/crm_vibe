import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { useMe, useUserMutations, useUsers } from '../../api/hooks'
import type { Role, User } from '../../api/types'
import { Dialog } from '../../components/Dialog'
import { useToast } from '../../components/Toast'
import { formatDateLong } from '../../lib/format'
import { MOBILE, useMediaQuery } from '../../lib/useMediaQuery'
import './settings.css'

const ROLES: { key: Role; label: string }[] = [
  { key: 'MANAGER', label: 'Менеджер' },
  { key: 'ADMIN', label: 'Администратор' },
]

const roleLabel = (role: Role) => ROLES.find((r) => r.key === role)!.label

const EMPTY = { displayName: '', login: '', password: '', role: 'MANAGER' as Role }

export function UsersPage() {
  const users = useUsers().data ?? []
  const isMobile = useMediaQuery(MOBILE)
  const { create } = useUserMutations()
  const toast = useToast()
  const [form, setForm] = useState(EMPTY)
  const error = create.error instanceof ApiError ? create.error : null

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate(form, {
      onSuccess: () => {
        toast.show(`Пользователь ${form.displayName.trim()} создан. Передайте ему логин и пароль.`)
        setForm(EMPTY)
        create.reset()
      },
    })
  }

  const field = (key: 'displayName' | 'login' | 'password') => ({
    className: `input${error?.fields[key] ? ' invalid' : ''}`,
    value: form[key],
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value }),
  })

  return (
    <div className="stack narrow">
      <div className="page-head">
        <div>
          <div className="eyebrow">Настройки</div>
          <h1 className="page-title">Пользователи</h1>
          <div className="page-subtitle">
            Сотрудники входят в CRM по логину и паролю. Менеджеры работают с лидами, администраторы ещё и настраивают
            статусы, теги и пользователей. Заблокированный пользователь сразу теряет доступ.
          </div>
        </div>
      </div>

      <form className="card" aria-label="Новый пользователь" onSubmit={submit} noValidate>
        <h2 className="card-title">Новый пользователь</h2>
        {error && !Object.keys(error.fields).length && <div className="form-error">{error.message}</div>}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
          <label className="field">
            Имя
            <input placeholder="Мария К." maxLength={100} {...field('displayName')} />
            {error?.fields.displayName && <span className="field-error">{error.fields.displayName}</span>}
          </label>
          <label className="field">
            Логин
            <input placeholder="maria" autoComplete="off" maxLength={64} {...field('login')} />
            {error?.fields.login && <span className="field-error">{error.fields.login}</span>}
          </label>
          <label className="field">
            Пароль
            <input type="text" placeholder="не короче 8 символов" autoComplete="new-password" maxLength={72} {...field('password')} />
            {error?.fields.password && <span className="field-error">{error.fields.password}</span>}
          </label>
          <label className="field">
            Роль
            <select className="select" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
              {ROLES.map((r) => (
                <option key={r.key} value={r.key}>
                  {r.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div>
          <button type="submit" className="btn btn-primary" disabled={create.isPending}>
            + Создать пользователя
          </button>
        </div>
      </form>

      {isMobile ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }} aria-label="Список пользователей">
          {users.map((u) => (
            <UserRow key={u.id} user={u} layout="card" />
          ))}
        </div>
      ) : (
        <section className="card flush" aria-label="Список пользователей">
          <div className="table-wrap">
            <table className="table middle" style={{ minWidth: 860 }}>
              <thead>
                <tr>
                  <th scope="col">Имя</th>
                  <th scope="col">Логин</th>
                  <th scope="col">Роль</th>
                  <th scope="col">Создан</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Действия
                  </th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <UserRow key={u.id} user={u} layout="row" />
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}

function UserRow({ user: u, layout }: { user: User; layout: 'row' | 'card' }) {
  const me = useMe().data
  const m = useUserMutations()
  const toast = useToast()
  const [editing, setEditing] = useState(false)
  const [name_, setName] = useState(u.displayName)
  const [role, setRole] = useState<Role>(u.role)
  const self = me?.id === u.id
  const [passwordOpen, setPasswordOpen] = useState(false)

  const save = () =>
    m.update.mutate(
      { id: u.id, displayName: name_.trim(), role },
      { onSuccess: () => (setEditing(false), toast.show('Сохранено')), onError: toast.error },
    )

  const toggleBlock = () => {
    if (u.active) {
      if (!window.confirm(`Заблокировать ${u.displayName}? Пользователь сразу потеряет доступ к CRM.`)) return
      m.block.mutate(u.id, { onError: toast.error })
    } else {
      m.unblock.mutate(u.id, { onError: toast.error })
    }
  }

  const name = editing ? (
    <input className="input" style={{ minHeight: 36 }} aria-label="Имя" value={name_} maxLength={100} onChange={(e) => setName(e.target.value)} />
  ) : (
    <span style={{ fontWeight: 700 }}>
      {u.displayName}
      {self && <span className="muted" style={{ fontWeight: 500 }}> · это вы</span>}
      {!u.active && (
        <span className="role-badge" style={{ marginLeft: 8, background: '#FDECEC', color: 'var(--danger)' }}>
          заблокирован
        </span>
      )}
    </span>
  )

  const roleView = editing ? (
    <select className="select" style={{ minHeight: 36 }} aria-label="Роль" value={role} disabled={self} onChange={(e) => setRole(e.target.value as Role)}>
      {ROLES.map((r) => (
        <option key={r.key} value={r.key}>
          {r.label}
        </option>
      ))}
    </select>
  ) : (
    <span
      className="role-badge"
      style={u.role === 'ADMIN' ? { background: '#EDE6FA', color: '#5B3BA6' } : { background: '#E4EFFB', color: '#1A5FA8' }}
    >
      {roleLabel(u.role)}
    </span>
  )

  const actions = (
    <div className="user-actions">
      {editing ? (
        <>
          <button type="button" className="btn btn-sm btn-primary" disabled={m.update.isPending || !name_.trim()} onClick={save}>
            Сохранить
          </button>
          <button type="button" className="btn btn-sm" onClick={() => (setEditing(false), setName(u.displayName), setRole(u.role))}>
            Отмена
          </button>
        </>
      ) : (
        <>
          <button type="button" className="btn btn-sm" onClick={() => setEditing(true)}>
            Изменить
          </button>
          <button type="button" className="btn btn-sm" onClick={() => setPasswordOpen(true)}>
            Сменить пароль
          </button>
          {!self && (
            <button type="button" className={`btn btn-sm${u.active ? ' btn-danger' : ''}`} onClick={toggleBlock}>
              {u.active ? 'Заблокировать' : 'Разблокировать'}
            </button>
          )}
        </>
      )}
      {passwordOpen && <PasswordDialog user={u} self={self} onClose={() => setPasswordOpen(false)} />}
    </div>
  )

  if (layout === 'card') {
    return (
      <article className="card" style={{ gap: 12, padding: 16, color: u.active ? undefined : 'var(--muted)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          {name}
          {!editing && roleView}
        </div>
        {editing && roleView}
        <div className="muted" style={{ fontSize: 13 }}>
          {u.login} · создан {formatDateLong(u.createdAt)}
        </div>
        {actions}
      </article>
    )
  }

  return (
    <tr style={u.active ? undefined : { color: 'var(--muted)' }}>
      <td>{name}</td>
      <td>{u.login}</td>
      <td>{roleView}</td>
      <td className="muted" style={{ fontSize: 13, whiteSpace: 'nowrap' }}>
        {formatDateLong(u.createdAt)}
      </td>
      <td>{actions}</td>
    </tr>
  )
}

function PasswordDialog({ user, self, onClose }: { user: User; self: boolean; onClose: () => void }) {
  const { password } = useUserMutations()
  const toast = useToast()
  const [value, setValue] = useState('')
  const [visible, setVisible] = useState(false)
  const error = password.error instanceof ApiError ? password.error : null
  const tooShort = value.length > 0 && value.length < 8

  const submit = (e: FormEvent) => {
    e.preventDefault()
    password.mutate(
      { id: user.id, password: value },
      {
        onSuccess: () => {
          toast.show(
            self
              ? 'Пароль изменён. На других устройствах нужно будет войти заново.'
              : `Пароль для ${user.displayName} изменён. Передайте его сотруднику.`,
          )
          onClose()
        },
      },
    )
  }

  return (
    <Dialog title={`Новый пароль — ${user.displayName}`} onClose={onClose}>
      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }} noValidate>
        <label className="field">
          Новый пароль
          <div style={{ display: 'flex', gap: 8 }}>
            <input
              className={`input${error?.fields.password ? ' invalid' : ''}`}
              type={visible ? 'text' : 'password'}
              autoComplete="new-password"
              autoFocus
              maxLength={72}
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
            <button type="button" className="btn btn-sm" style={{ minHeight: 44 }} onClick={() => setVisible(!visible)}>
              {visible ? 'Скрыть' : 'Показать'}
            </button>
          </div>
          <span className={error?.fields.password || tooShort ? 'field-error' : 'muted'} style={{ fontWeight: 500 }}>
            {error?.fields.password ?? 'Не короче 8 символов'}
          </span>
        </label>
        {error && !error.fields.password && <div className="form-error">{error.message}</div>}
        <div className="muted" style={{ fontSize: 13 }}>
          {self
            ? 'Вы останетесь в системе на этом устройстве, на остальных потребуется войти заново.'
            : 'Сотрудника выведет из системы на всех устройствах — дальше он входит с новым паролем.'}
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button type="button" className="btn" onClick={onClose}>
            Отмена
          </button>
          <button type="submit" className="btn btn-primary" disabled={value.length < 8 || password.isPending}>
            {password.isPending ? 'Сохраняем…' : 'Сохранить пароль'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
