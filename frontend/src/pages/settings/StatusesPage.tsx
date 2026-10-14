import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { useStatusMutations, useStatuses } from '../../api/hooks'
import type { Status, StatusColor } from '../../api/types'
import { Icon } from '../../components/Icon'
import { Swatches } from '../../components/Swatches'
import { useToast } from '../../components/Toast'
import { plural } from '../../lib/format'
import { STATUS_COLORS } from '../../lib/palette'
import './settings.css'

const COLOR_OPTIONS = (Object.keys(STATUS_COLORS) as StatusColor[]).map((key) => ({
  key,
  color: STATUS_COLORS[key].dot,
  label: STATUS_COLORS[key].label,
}))

export function StatusesPage() {
  const statuses = useStatuses().data ?? []
  const m = useStatusMutations()
  const toast = useToast()
  const [draft, setDraft] = useState('')
  const [color, setColor] = useState<StatusColor>('PINK')
  const createError = m.create.error instanceof ApiError ? m.create.error : null

  const create = (e: FormEvent) => {
    e.preventDefault()
    m.create.mutate(
      { name: draft.trim(), color },
      { onSuccess: () => (setDraft(''), toast.show('Статус создан')) },
    )
  }

  const move = (index: number, delta: number) => {
    const ids = statuses.map((s) => s.id)
    const j = index + delta
    if (j < 0 || j >= ids.length) return
    ;[ids[index], ids[j]] = [ids[j], ids[index]]
    m.reorder.mutate(ids, { onError: toast.error })
  }

  const remove = (s: Status) => {
    const fallback = statuses.find((x) => x.id !== s.id)
    const warning = s.leadCount
      ? `Удалить статус «${s.name}»? ${s.leadCount} ${plural(s.leadCount, 'лид перейдёт', 'лида перейдут', 'лидов перейдут')} в «${fallback?.name}».`
      : `Удалить статус «${s.name}»?`
    if (window.confirm(warning)) m.remove.mutate(s.id, { onError: toast.error, onSuccess: () => toast.show('Статус удалён') })
  }

  return (
    <div className="stack narrow">
      <div className="page-head">
        <div>
          <div className="eyebrow">Настройки</div>
          <h1 className="page-title">Статусы лидов</h1>
          <div className="page-subtitle">
            Порядок статусов — это этапы вашей воронки. В таком же порядке они показываются в фильтрах, карточке лида и на
            дашборде. Новые лиды из Telegram получают первый статус.
          </div>
        </div>
      </div>

      <form className="card create-form" aria-label="Новый статус" onSubmit={create}>
        <label className="field">
          Название статуса
          <input
            className={`input${createError?.fields.name ? ' invalid' : ''}`}
            placeholder="Например: Отправлен счёт"
            value={draft}
            maxLength={64}
            onChange={(e) => setDraft(e.target.value)}
          />
          {createError && <span className="field-error">{createError.fields.name ?? createError.message}</span>}
        </label>
        <div className="field">
          Цвет
          <Swatches options={COLOR_OPTIONS} value={color} onChange={setColor} />
        </div>
        <button type="submit" className="btn btn-primary" disabled={!draft.trim() || m.create.isPending}>
          + Создать статус
        </button>
      </form>

      <section className="card flush" aria-label="Список статусов">
        <div className="status-grid head">
          <span>№</span>
          <span>Статус</span>
          <span style={{ textAlign: 'right' }}>Лидов</span>
          <span style={{ textAlign: 'right' }}>Действия</span>
        </div>
        {statuses.map((s, i) => (
          <StatusRow
            key={s.id}
            status={s}
            index={i}
            last={i === statuses.length - 1}
            onMove={move}
            onRemove={remove}
            onlyOne={statuses.length === 1}
          />
        ))}
        <div className="note">
          Меняйте порядок стрелками. При удалении статуса его лиды переходят в «{statuses[0]?.name ?? '—'}» (или в
          следующий, если удаляется первый). Статус «Сделка» считается на дашборде как сделка, даже если его переименовать.
        </div>
      </section>
    </div>
  )
}

function StatusRow({
  status: s,
  index,
  last,
  onMove,
  onRemove,
  onlyOne,
}: {
  status: Status
  index: number
  last: boolean
  onMove: (index: number, delta: number) => void
  onRemove: (s: Status) => void
  onlyOne: boolean
}) {
  const { update } = useStatusMutations()
  const toast = useToast()
  const [name, setName] = useState(s.name)
  const c = STATUS_COLORS[s.color]

  const save = () => {
    const value = name.trim()
    if (!value || value === s.name) {
      setName(s.name)
      return
    }
    update.mutate(
      { id: s.id, name: value, color: s.color },
      {
        onError: (e) => {
          setName(s.name)
          toast.error(e)
        },
      },
    )
  }

  return (
    <div className="status-grid">
      <div className="status-n num" style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--muted-2)', fontWeight: 700, fontSize: 13 }}>
        {index + 1}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
        <span className="dot" style={{ width: 12, height: 12, background: c.dot }} />
        <input
          className="status-name-input"
          aria-label="Название статуса"
          value={name}
          maxLength={64}
          onChange={(e) => setName(e.target.value)}
          onBlur={save}
          onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
        />
        <span className="pill" style={{ background: c.bg, color: c.fg }}>
          {s.won ? 'сделка' : s.name}
        </span>
      </div>
      <span className="status-count num" style={{ textAlign: 'right', fontWeight: 700 }}>
        {s.leadCount} <span className="only-mobile">{plural(s.leadCount, 'лид', 'лида', 'лидов')}</span>
      </span>
      <div className="status-actions" style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
        <button type="button" className="btn btn-square" aria-label="Поднять выше" disabled={index === 0} onClick={() => onMove(index, -1)}>
          ↑
        </button>
        <button type="button" className="btn btn-square" aria-label="Опустить ниже" disabled={last} onClick={() => onMove(index, 1)}>
          ↓
        </button>
        <button type="button" className="btn btn-square btn-danger" aria-label="Удалить статус" disabled={onlyOne} onClick={() => onRemove(s)}>
          <Icon name="trash" size={16} />
        </button>
      </div>
    </div>
  )
}
