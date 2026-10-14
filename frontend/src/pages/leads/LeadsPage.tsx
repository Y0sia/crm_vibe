import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useLeads, useLeadsInfinite, useManagers, useSetLeadStatus, useStatuses, useTags } from '../../api/hooks'
import type { LeadListItem, LeadPage, Status } from '../../api/types'
import { Icon } from '../../components/Icon'
import { StatusSelect } from '../../components/StatusSelect'
import { TagLabel } from '../../components/TagLabel'
import { useToast } from '../../components/Toast'
import { formatMessageDate } from '../../lib/format'
import { countActive, filtersToSearch, parseFilters, parsePage, type LeadFilters as Filters } from '../../lib/leadFilter'
import { sourceLabel } from '../../lib/palette'
import { MOBILE, useMediaQuery } from '../../lib/useMediaQuery'
import { LeadFilters } from './LeadFilters'
import './leads.css'

const PAGE_SIZE = 25

function useDebounced<T>(value: T, ms: number) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), ms)
    return () => window.clearTimeout(t)
  }, [value, ms])
  return debounced
}

export function LeadsPage() {
  const [search, setSearch] = useSearchParams()
  const filters = useMemo(() => parseFilters(search), [search])
  const page = parsePage(search)
  const isMobile = useMediaQuery(MOBILE)
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState(filters.q)
  const debouncedQ = useDebounced(q, 300)

  const statuses = useStatuses().data ?? []
  const tags = useTags().data ?? []
  const managers = useManagers().data ?? []

  const update = (patch: Partial<Filters>) => setSearch(filtersToSearch({ ...filters, ...patch }, 0), { replace: true })
  const reset = () => {
    setQ('')
    setSearch(new URLSearchParams(), { replace: true })
  }

  useEffect(() => {
    if (debouncedQ !== filters.q) update({ q: debouncedQ })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQ])

  const paged = useLeads(filters, page, PAGE_SIZE)
  const infinite = useLeadsInfinite(filters, isMobile)
  const data: LeadPage | undefined = isMobile ? infinite.data?.pages.at(-1) : paged.data
  const rows: LeadListItem[] = isMobile ? (infinite.data?.pages.flatMap((p) => p.items) ?? []) : (paged.data?.items ?? [])
  const found = data?.found ?? 0
  const total = data?.total ?? 0
  const active = countActive(filters)
  const loading = isMobile ? infinite.isPending : paged.isPending

  return (
    <>
      <div className="page-head leads-head">
        <div className="only-desktop">
          <h1 className="page-title">Лиды</h1>
          <div className="page-subtitle">
            Найдено {found} из {total}
          </div>
        </div>
        <button
          type="button"
          className={`btn filter-toggle only-desktop${active ? ' active' : ''}`}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Icon name="filter" size={16} />
          Фильтр
          {active > 0 && <span className="filter-count">{active}</span>}
        </button>
        {open && (
          <LeadFilters
            filters={filters}
            onChange={update}
            onReset={reset}
            onClose={() => setOpen(false)}
            statuses={statuses}
            tags={tags}
            managers={managers}
            statusCounts={data?.statusCounts ?? {}}
            found={found}
          />
        )}
      </div>

      <div className="search">
        <Icon name="search" />
        <input
          type="search"
          aria-label="Поиск лида"
          placeholder={isMobile ? 'Имя или @username' : 'Поиск по имени или @username'}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      </div>

      {isMobile ? (
        <MobileList
          rows={rows}
          statuses={statuses}
          found={found}
          total={total}
          active={active}
          loading={loading}
          onOpenFilters={() => setOpen(true)}
          hasMore={!!infinite.hasNextPage}
          loadingMore={infinite.isFetchingNextPage}
          onMore={() => infinite.fetchNextPage()}
          onReset={reset}
        />
      ) : (
        <section className="card flush" aria-label="Список лидов">
          <div className="table-wrap">
            <table className="table" style={{ minWidth: 1080 }}>
              <thead>
                <tr>
                  <th scope="col">Лид</th>
                  <th scope="col">Статус</th>
                  <th scope="col">Теги</th>
                  <th scope="col">Менеджер</th>
                  <th scope="col">Источник</th>
                  <th scope="col">Последнее сообщение</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Дата сообщения
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((lead) => (
                  <LeadRow key={lead.id} lead={lead} statuses={statuses} />
                ))}
              </tbody>
            </table>
          </div>
          {!loading && rows.length === 0 && <EmptyState total={total} onReset={reset} />}
          <Pager page={page} found={found} shown={rows.length} onPage={(p) => setSearch(filtersToSearch(filters, p))} />
        </section>
      )}
    </>
  )
}

function useLeadLink() {
  const location = useLocation()
  return (id: number) => ({ pathname: `/leads/${id}`, state: { back: location.search } })
}

function LeadRow({ lead, statuses }: { lead: LeadListItem; statuses: Status[] }) {
  const setStatus = useSetLeadStatus()
  const toast = useToast()
  const link = useLeadLink()(lead.id)
  return (
    <tr>
      <td>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Link to={link.pathname} state={link.state} className="lead-name">
            {lead.name}
          </Link>
          {lead.username && <span className="lead-sub">@{lead.username}</span>}
        </div>
      </td>
      <td>
        {statuses.length > 0 && (
          <StatusSelect
            statuses={statuses}
            value={lead.statusId}
            disabled={setStatus.isPending}
            onChange={(statusId) => setStatus.mutate({ id: lead.id, statusId }, { onError: toast.error })}
          />
        )}
      </td>
      <td style={{ width: 260 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {lead.tags.map((t) => (
            <span key={t.id} className="tag">
              <TagLabel name={t.name} color={t.color} />
            </span>
          ))}
          {lead.tags.length === 0 && <span style={{ fontSize: 12, color: 'var(--muted-2)' }}>—</span>}
        </div>
      </td>
      <td style={{ fontWeight: 600, whiteSpace: 'nowrap', color: lead.manager ? 'var(--ink)' : 'var(--warn)' }}>
        {lead.manager?.displayName ?? 'Не назначен'}
      </td>
      <td>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--ink-2)', whiteSpace: 'nowrap' }}>
          <span style={{ color: 'var(--accent)', display: 'inline-flex' }}>
            <Icon name="plane" size={14} strokeWidth={2.2} />
          </span>
          {sourceLabel(lead.source)}
        </span>
      </td>
      <td style={{ color: 'var(--ink-2)', maxWidth: 280 }}>
        <div className="ellipsis">{lead.lastMessageText ?? '—'}</div>
      </td>
      <td className="num" style={{ textAlign: 'right', color: 'var(--muted)', fontSize: 13, whiteSpace: 'nowrap' }}>
        {lead.lastMessageAt ? formatMessageDate(lead.lastMessageAt) : '—'}
      </td>
    </tr>
  )
}

function EmptyState({ total, onReset }: { total: number; onReset: () => void }) {
  if (total === 0) {
    return (
      <div className="empty">
        Лидов пока нет. Как только кто-нибудь напишет в Telegram аккаунту компании, он появится здесь.
      </div>
    )
  }
  return (
    <div className="empty">
      По выбранным фильтрам лидов нет.{' '}
      <button type="button" className="btn btn-link" onClick={onReset}>
        Сбросить фильтры
      </button>
    </div>
  )
}

function Pager({ page, found, shown, onPage }: { page: number; found: number; shown: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(found / PAGE_SIZE))
  const windowStart = Math.max(0, Math.min(page - 2, pages - 5))
  const numbers = Array.from({ length: Math.min(5, pages) }, (_, i) => windowStart + i)
  return (
    <div className="pager">
      <span>
        Показано {shown} · {PAGE_SIZE} на странице
      </span>
      {pages > 1 && (
        <div className="pager-buttons">
          <button type="button" aria-label="Предыдущая страница" disabled={page === 0} onClick={() => onPage(page - 1)}>
            ‹
          </button>
          {numbers.map((n) => (
            <button key={n} type="button" aria-current={n === page ? 'page' : undefined} onClick={() => onPage(n)}>
              {n + 1}
            </button>
          ))}
          <button
            type="button"
            aria-label="Следующая страница"
            disabled={page >= pages - 1}
            onClick={() => onPage(page + 1)}
          >
            ›
          </button>
        </div>
      )}
    </div>
  )
}

function MobileList(props: {
  rows: LeadListItem[]
  statuses: Status[]
  found: number
  total: number
  active: number
  loading: boolean
  onOpenFilters: () => void
  hasMore: boolean
  loadingMore: boolean
  onMore: () => void
  onReset: () => void
}) {
  const { rows, statuses, found, total, active, loading } = props
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span className="muted" style={{ fontSize: 14 }}>
          Найдено {found} из {total}
        </span>
        <button type="button" className={`btn filter-toggle${active ? ' active' : ''}`} onClick={props.onOpenFilters}>
          <Icon name="filter" size={16} />
          Фильтр
          {active > 0 && <span className="filter-count">{active}</span>}
        </button>
      </div>
      {rows.map((lead) => (
        <MobileLeadCard key={lead.id} lead={lead} statuses={statuses} />
      ))}
      {!loading && rows.length === 0 && (
        <div className="card">
          <EmptyState total={total} onReset={props.onReset} />
        </div>
      )}
      {props.hasMore && (
        <button type="button" className="btn" style={{ minHeight: 48 }} disabled={props.loadingMore} onClick={props.onMore}>
          {props.loadingMore ? 'Загружаем…' : 'Показать ещё'}
        </button>
      )}
    </>
  )
}

function MobileLeadCard({ lead, statuses }: { lead: LeadListItem; statuses: Status[] }) {
  const setStatus = useSetLeadStatus()
  const toast = useToast()
  const link = useLeadLink()(lead.id)
  return (
    <article className="lead-card">
      <div className="lead-card-top">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
          <Link to={link.pathname} state={link.state} className="lead-name">
            {lead.name}
          </Link>
          <span className="lead-sub" style={{ fontSize: 13 }}>
            {lead.username ? `@${lead.username} · ` : ''}
            {sourceLabel(lead.source)}
          </span>
        </div>
        <span style={{ flex: 'none', fontSize: 12, color: 'var(--muted)', whiteSpace: 'nowrap' }}>
          {lead.lastMessageAt ? formatMessageDate(lead.lastMessageAt) : ''}
        </span>
      </div>
      <div className="ellipsis" style={{ fontSize: 14, color: 'var(--ink-2)' }}>
        {lead.lastMessageText ?? '—'}
      </div>
      <div className="lead-card-meta">
        {statuses.length > 0 && (
          <StatusSelect
            statuses={statuses}
            value={lead.statusId}
            disabled={setStatus.isPending}
            onChange={(statusId) => setStatus.mutate({ id: lead.id, statusId }, { onError: toast.error })}
          />
        )}
        {lead.tags.map((t) => (
          <span key={t.id} className="tag">
            <TagLabel name={t.name} color={t.color} />
          </span>
        ))}
        <span
          style={{ marginLeft: 'auto', fontSize: 12, fontWeight: 700, color: lead.manager ? 'var(--ink)' : 'var(--warn)' }}
        >
          {lead.manager?.displayName ?? 'Не назначен'}
        </span>
      </div>
    </article>
  )
}

