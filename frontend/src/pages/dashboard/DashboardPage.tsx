import { useState } from 'react'
import { Link } from 'react-router'
import { useDashboard, useMe } from '../../api/hooks'
import type { Dashboard } from '../../api/types'
import { TagLabel } from '../../components/TagLabel'
import { addDays, formatDuration, formatShortDay, localDateISO, plural } from '../../lib/format'
import { STATUS_COLORS, sourceColor, sourceLabel } from '../../lib/palette'
import { MOBILE, useMediaQuery } from '../../lib/useMediaQuery'
import './dashboard.css'

type Period = '7' | '30' | 'q' | 'custom'

const PERIODS: { id: Period; name: string }[] = [
  { id: '7', name: '7 дней' },
  { id: '30', name: '30 дней' },
  { id: 'q', name: 'Квартал' },
  { id: 'custom', name: 'Свой период' },
]

function range(period: Period, custom: { from: string; to: string }) {
  const today = new Date()
  if (period === 'custom') return custom
  const days = period === '7' ? 7 : period === '30' ? 30 : 90
  return { from: localDateISO(addDays(today, -(days - 1))), to: localDateISO(today) }
}

export function DashboardPage() {
  const [period, setPeriod] = useState<Period>('30')
  const initial = range('30', { from: '', to: '' })
  const [draft, setDraft] = useState(initial)
  const [custom, setCustom] = useState(initial)
  const { from, to } = range(period, custom)
  const dashboard = useDashboard(from, to)
  const isAdmin = useMe().data?.role === 'ADMIN'
  const d = dashboard.data

  return (
    <>
      <div className="page-head">
        <div className="only-desktop">
          <h1 className="page-title">Дашборд</h1>
        </div>
        <div className="period">
          <div className="seg" role="group" aria-label="Период">
            {PERIODS.map((p) => (
              <button key={p.id} type="button" aria-pressed={p.id === period} onClick={() => setPeriod(p.id)}>
                {p.name}
              </button>
            ))}
          </div>
          {period === 'custom' && (
            <form
              className="period-custom"
              onSubmit={(e) => {
                e.preventDefault()
                if (draft.from && draft.to) setCustom(draft)
              }}
            >
              <label>
                с <input type="date" value={draft.from} max={draft.to} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
              </label>
              <label>
                по <input type="date" value={draft.to} min={draft.from} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
              </label>
              <button type="submit" className="btn btn-primary btn-sm">
                Применить
              </button>
            </form>
          )}
        </div>
      </div>

      {dashboard.isError && <div className="form-error">{dashboard.error.message}</div>}
      {!d ? (
        <div className="muted">Загрузка…</div>
      ) : (
        <>
          <div className="grid-2">
            <NewLeadsChart days={d.newLeadsByDay} />
            <section className="card" aria-label="Лиды по статусам">
              <h2 className="card-title">Лиды по статусам</h2>
              <HBars
                rows={d.byStatus.map((s) => ({ key: s.statusId, label: s.name, value: s.count, color: STATUS_COLORS[s.color].dot }))}
                labelWidth={130}
                height={22}
              />
            </section>
          </div>
          <div className="grid-3">
            <Sources data={d} />
            <section className="card" aria-label="Теги">
              <div className="card-head">
                <h2 className="card-title">Теги</h2>
                {isAdmin && (
                  <Link to="/settings/tags" className="card-link">
                    Все теги
                  </Link>
                )}
              </div>
              <HBars
                rows={d.byTag.map((t) => ({ key: t.tagId, label: <TagLabel name={t.name} color={t.color} />, value: t.count, color: '#2B85DA' }))}
                labelWidth={110}
                height={10}
              />
              {d.byTag.length === 0 && <span className="muted">Тегов пока нет</span>}
            </section>
            <Managers data={d} />
          </div>
          <div className="muted" style={{ fontSize: 13 }}>
            Статистика по лидам, которые впервые написали за выбранный период. Время ответа — среднее по ответам из CRM.
          </div>
        </>
      )}
    </>
  )
}

function NewLeadsChart({ days }: { days: Dashboard['newLeadsByDay'] }) {
  const isMobile = useMediaQuery(MOBILE)
  const max = Math.max(1, ...days.map((d) => d.count))
  const total = days.reduce((s, d) => s + d.count, 0)
  const showValues = days.length <= (isMobile ? 14 : 31)
  // подпись «17 сен» шире столбика — показываем каждую n-ю, считая от сегодняшнего дня
  const labelEvery = Math.ceil(days.length / (isMobile ? 4 : 8))
  const gap = days.length > 31 ? 2 : days.length > 14 ? 4 : 8
  return (
    <section className="card" aria-label="Новые лиды по дням">
      <div className="card-head">
        <h2 className="card-title">Новые лиды по дням</h2>
        <span className="muted" style={{ fontSize: 13 }}>
          {total} {plural(total, 'лид', 'лида', 'лидов')} за {days.length} {plural(days.length, 'день', 'дня', 'дней')}
        </span>
      </div>
      <div className="bars" style={{ gap }}>
        {days.map((d, i) => (
          <div key={d.date} className="bar-col" title={`${formatShortDay(d.date)}: ${d.count}`}>
            {showValues && d.count > 0 && <span className="num">{d.count}</span>}
            {d.count > 0 && (
              <div
                className="bar"
                style={{ height: `${(d.count / max) * 80}%`, background: i === days.length - 1 ? '#164C86' : '#2B85DA' }}
              />
            )}
          </div>
        ))}
      </div>
      <div className="bar-labels" style={{ gap }}>
        {days.map((d, i) => (
          <span key={d.date} className="num" style={i === days.length - 1 ? { justifyContent: 'flex-end' } : undefined}>
            {(days.length - 1 - i) % labelEvery === 0 ? formatShortDay(d.date) : ''}
          </span>
        ))}
      </div>
    </section>
  )
}

function HBars({
  rows,
  labelWidth,
  height,
}: {
  rows: { key: number; label: React.ReactNode; value: number; color: string }[]
  labelWidth: number
  height: number
}) {
  const max = Math.max(1, ...rows.map((r) => r.value))
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {rows.map((r) => (
        <div key={r.key} className="hbar" style={{ gridTemplateColumns: `${labelWidth}px 1fr 44px` }}>
          <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.label}</span>
          <div className="hbar-track" style={{ height, borderRadius: height / 2 > 6 ? 6 : height / 2 }}>
            <div style={{ width: `${(r.value / max) * 100}%`, background: r.color, borderRadius: 'inherit' }} />
          </div>
          <span className="num" style={{ textAlign: 'right', fontWeight: 800 }}>
            {r.value}
          </span>
        </div>
      ))}
    </div>
  )
}

function Sources({ data }: { data: Dashboard }) {
  const rows = data.bySource.filter((r) => r.count > 0)
  const total = rows.reduce((s, r) => s + r.count, 0)
  return (
    <section className="card" aria-label="Источники">
      <h2 className="card-title">Источники в Telegram</h2>
      <div className="source-strip">
        {rows
          .filter((r) => r.count > 0)
          .map((r) => (
            <div key={r.source ?? 'none'} style={{ width: `${(r.count / Math.max(total, 1)) * 100}%`, background: sourceColor(r.source) }} />
          ))}
      </div>
      {rows.length === 0 && <span className="muted">За этот период новых лидов нет</span>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {rows.map((r) => (
          <div key={r.source ?? 'none'} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 14 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: sourceColor(r.source), flex: 'none' }} />
            <span style={{ fontWeight: 600 }}>{sourceLabel(r.source)}</span>
            <span className="num" style={{ marginLeft: 'auto', fontWeight: 800 }}>
              {r.count}
            </span>
            <span className="num muted" style={{ width: 44, textAlign: 'right' }}>
              {r.percent}%
            </span>
          </div>
        ))}
      </div>
      {total > 0 && rows.some((r) => !r.source) && (
        <span className="muted" style={{ fontSize: 12 }}>
          Источник указывает менеджер в карточке лида
        </span>
      )}
    </section>
  )
}

function Managers({ data }: { data: Dashboard }) {
  return (
    <section className="card" aria-label="Менеджеры" style={{ gap: 12 }}>
      <h2 className="card-title">Менеджеры</h2>
      <div className="table-wrap">
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ color: 'var(--muted)', textAlign: 'left' }}>
              <th scope="col" style={{ padding: '8px 0', fontWeight: 700 }}>
                Имя
              </th>
              <th scope="col" style={{ padding: 8, fontWeight: 700, textAlign: 'right' }}>
                Лиды
              </th>
              <th scope="col" style={{ padding: 8, fontWeight: 700, textAlign: 'right' }}>
                Сделки
              </th>
              <th scope="col" style={{ padding: '8px 0 8px 8px', fontWeight: 700, textAlign: 'right' }}>
                Ответ
              </th>
            </tr>
          </thead>
          <tbody>
            {data.managers.map((m) => {
              const td = { padding: '10px 8px', borderTop: '1px solid var(--line-2)', textAlign: 'right' as const }
              return (
                <tr key={m.managerId ?? 'none'}>
                  <td style={{ ...td, textAlign: 'left', paddingLeft: 0, fontWeight: 700, color: m.managerId ? 'var(--ink)' : 'var(--warn)' }}>
                    {m.name}
                  </td>
                  <td className="num" style={td}>
                    {m.leads}
                  </td>
                  <td className="num" style={td}>
                    {m.deals ?? '—'}
                  </td>
                  <td className="num" style={{ ...td, paddingRight: 0, whiteSpace: 'nowrap' }}>
                    {m.managerId ? formatDuration(m.avgResponseSeconds) : '—'}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}
