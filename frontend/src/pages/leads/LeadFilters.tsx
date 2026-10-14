import type { Status, Tag, UserRef } from '../../api/types'
import { Icon } from '../../components/Icon'
import { TagLabel } from '../../components/TagLabel'
import { plural } from '../../lib/format'
import type { LeadFilters as Filters } from '../../lib/leadFilter'
import { SOURCES, STATUS_COLORS } from '../../lib/palette'
import './leads.css'

const toggle = (list: number[], id: number) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id])

/** Панель фильтров: на десктопе — всплывающая под кнопкой, на телефоне — на весь экран. */
export function LeadFilters({
  filters,
  onChange,
  onReset,
  onClose,
  statuses,
  tags,
  managers,
  statusCounts,
  found,
}: {
  filters: Filters
  onChange: (patch: Partial<Filters>) => void
  onReset: () => void
  onClose: () => void
  statuses: Status[]
  tags: Tag[]
  managers: UserRef[]
  statusCounts: Record<string, number>
  found: number
}) {
  return (
    <section className="filter-panel" aria-label="Фильтры">
      <div className="filter-head">
        <button type="button" className="filter-close" aria-label="Закрыть фильтр" onClick={onClose}>
          ×
        </button>
        <h2 className="card-title">Фильтр</h2>
        <button type="button" className="btn btn-link only-mobile" onClick={onReset}>
          Сбросить
        </button>
      </div>

      <div className="filter-body">
        <div className="filter-grid">
          <label className="field span-2">
            Текст последнего сообщения
            <input
              className="input"
              type="search"
              placeholder="Например, прайс"
              value={filters.messageText}
              onChange={(e) => onChange({ messageText: e.target.value })}
            />
          </label>
          <label className="field">
            Последнее сообщение — с
            <input className="input" type="date" value={filters.from} onChange={(e) => onChange({ from: e.target.value })} />
          </label>
          <label className="field">
            по
            <input className="input" type="date" value={filters.to} onChange={(e) => onChange({ to: e.target.value })} />
          </label>
          <label className="field">
            Менеджер
            <select className="select" value={filters.manager} onChange={(e) => onChange({ manager: e.target.value })}>
              <option value="all">Все менеджеры</option>
              <option value="none">Не назначен</option>
              {managers.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.displayName}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Источник
            <select className="select" value={filters.source} onChange={(e) => onChange({ source: e.target.value })}>
              <option value="all">Все источники</option>
              <option value="none">Не указан</option>
              {SOURCES.map((s) => (
                <option key={s.key} value={s.key}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="filter-section">
          <span className="field">Статус</span>
          <div className="chips">
            {statuses.map((s) => (
              <button
                key={s.id}
                type="button"
                className="chip"
                aria-pressed={filters.statusIds.includes(s.id)}
                onClick={() => onChange({ statusIds: toggle(filters.statusIds, s.id) })}
              >
                <span className="dot" style={{ background: STATUS_COLORS[s.color].dot }} />
                {s.name}
                <span className="num" style={{ opacity: 0.75 }}>
                  {statusCounts[s.id] ?? 0}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="filter-section">
          <span className="field">Теги</span>
          <div className="chips">
            {tags.map((t) => (
              <button
                key={t.id}
                type="button"
                className="chip square"
                aria-pressed={filters.tagIds.includes(t.id)}
                onClick={() => onChange({ tagIds: toggle(filters.tagIds, t.id) })}
              >
                <TagLabel name={t.name} color={t.color} />
              </button>
            ))}
            {tags.length === 0 && <span className="muted">Тегов пока нет</span>}
          </div>
        </div>
      </div>

      <div className="filter-foot">
        <button type="button" className="btn btn-link only-desktop" onClick={onReset}>
          Сбросить
        </button>
        <button type="button" className="btn btn-primary" onClick={onClose}>
          <Icon name="filter" size={16} />
          Показать {found} {plural(found, 'лида', 'лида', 'лидов')}
        </button>
      </div>
    </section>
  )
}
