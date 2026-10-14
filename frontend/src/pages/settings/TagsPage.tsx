import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { useTagMutations, useTags } from '../../api/hooks'
import type { Tag, TagColor } from '../../api/types'
import { Swatches } from '../../components/Swatches'
import { useToast } from '../../components/Toast'
import { plural } from '../../lib/format'
import { TAG_COLORS } from '../../lib/palette'
import './settings.css'

const COLOR_OPTIONS = (Object.keys(TAG_COLORS) as TagColor[]).map((key) => ({
  key,
  color: TAG_COLORS[key].c,
  label: TAG_COLORS[key].label,
}))

export function TagsPage() {
  const tags = useTags().data ?? []
  const m = useTagMutations()
  const toast = useToast()
  const [draft, setDraft] = useState('')
  const [color, setColor] = useState<TagColor>('TEAL')
  const createError = m.create.error instanceof ApiError ? m.create.error : null

  const create = (e: FormEvent) => {
    e.preventDefault()
    m.create.mutate({ name: draft.trim(), color }, { onSuccess: () => (setDraft(''), toast.show('Тег создан')) })
  }

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow">Настройки</div>
          <h1 className="page-title">Теги</h1>
          <div className="page-subtitle">
            Теги помогают группировать лидов: у одного лида может быть сколько угодно тегов. По ним работает фильтр в списке
            лидов.
          </div>
        </div>
      </div>

      <form className="card create-form" aria-label="Новый тег" onSubmit={create}>
        <label className="field">
          Название тега
          <input
            className={`input${createError?.fields.name ? ' invalid' : ''}`}
            placeholder="Например: Из рекламы"
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
          + Создать тег
        </button>
      </form>

      <div className="card-head">
        <h2 className="card-title">Все теги · {tags.length}</h2>
        <span className="muted only-desktop" style={{ fontSize: 13 }}>
          Нажмите «Изменить», чтобы переименовать тег или сменить цвет
        </span>
      </div>
      <div className="tag-cards">
        {tags.map((t) => (
          <TagCard key={t.id} tag={t} />
        ))}
      </div>
      <div className="muted" style={{ fontSize: 13 }}>
        Удалённый тег снимается со всех лидов. Сами лиды при этом не удаляются.
      </div>
    </>
  )
}

function TagCard({ tag }: { tag: Tag }) {
  const { update, remove } = useTagMutations()
  const toast = useToast()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(tag.name)
  const [color, setColor] = useState<TagColor>(tag.color)

  const finish = () => {
    const value = name.trim()
    if (!value || (value === tag.name && color === tag.color)) {
      setName(tag.name)
      setColor(tag.color)
      setEditing(false)
      return
    }
    update.mutate({ id: tag.id, name: value, color }, { onSuccess: () => setEditing(false), onError: toast.error })
  }

  const del = () => {
    const warning = tag.leadCount
      ? `Удалить тег «${tag.name}»? Он снимется с ${tag.leadCount} ${plural(tag.leadCount, 'лида', 'лидов', 'лидов')}.`
      : `Удалить тег «${tag.name}»?`
    if (window.confirm(warning)) remove.mutate(tag.id, { onError: toast.error, onSuccess: () => toast.show('Тег удалён') })
  }

  return (
    <div className={`tag-card${editing ? ' editing' : ''}`}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span className="tag-badge" style={{ background: TAG_COLORS[editing ? color : tag.color].c }}>
          #
        </span>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
          {editing ? (
            <input
              className="input"
              style={{ minHeight: 36, fontWeight: 700, fontSize: 15, borderColor: 'var(--primary)' }}
              aria-label="Название тега"
              autoFocus
              value={name}
              maxLength={64}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && finish()}
            />
          ) : (
            <span style={{ fontSize: 15, fontWeight: 800, overflowWrap: 'anywhere' }}>{tag.name}</span>
          )}
          <span className="muted num" style={{ fontSize: 12 }}>
            {tag.leadCount} {plural(tag.leadCount, 'лид', 'лида', 'лидов')}
          </span>
        </div>
      </div>
      {editing && <Swatches options={COLOR_OPTIONS} value={color} onChange={setColor} small />}
      <div className="tag-card-actions">
        <button type="button" className="btn btn-sm" disabled={update.isPending} onClick={() => (editing ? finish() : setEditing(true))}>
          {editing ? 'Готово' : 'Изменить'}
        </button>
        <button type="button" className="btn btn-sm btn-danger" disabled={remove.isPending} onClick={del}>
          Удалить
        </button>
      </div>
    </div>
  )
}
