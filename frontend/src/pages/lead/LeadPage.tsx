import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { useLead, useLeadEvents, useLeadMessages, useLeadMutations, useManagers, useMe, useStatuses, useTags } from '../../api/hooks'
import type { LeadCard, LeadEvent, LeadSource, Message, Status, Tag } from '../../api/types'
import { Icon } from '../../components/Icon'
import { TagLabel } from '../../components/TagLabel'
import { useToast } from '../../components/Toast'
import { formatDateLong, formatDayHeading, formatMessageDate, formatPhone, formatTelegramId, formatTime, initials } from '../../lib/format'
import { SOURCES, STATUS_COLORS, TAG_COLORS, avatarColor } from '../../lib/palette'
import './lead.css'

export function LeadPage() {
  const id = Number(useParams().id)
  const location = useLocation()
  const back = `/leads${(location.state as { back?: string } | null)?.back ?? ''}`
  const lead = useLead(id)
  const statuses = useStatuses().data ?? []
  const tags = useTags().data ?? []

  if (lead.isPending) return <div className="muted">Загрузка…</div>
  if (lead.isError) {
    return (
      <>
        <Link to={back} className="back-link">
          ‹ Все лиды
        </Link>
        <div className="card empty">{lead.error instanceof ApiError ? lead.error.message : 'Не удалось загрузить лида'}</div>
      </>
    )
  }
  const l = lead.data

  return (
    <>
      <Link to={back} className="back-link">
        ‹ Все лиды
      </Link>
      <div className="lead-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, minWidth: 0 }}>
          <div className="avatar" aria-hidden="true" style={{ width: 60, height: 60, fontSize: 20, fontWeight: 800, background: avatarColor(l.id) }}>
            {initials(l.name)}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
            <h1>{l.name}</h1>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, fontSize: 14, color: 'var(--muted)' }}>
              {l.username && <span>@{l.username}</span>}
              {l.username && l.lastMessageAt && <span>·</span>}
              {l.lastMessageAt && <span>{formatMessageDate(l.lastMessageAt)}</span>}
            </div>
          </div>
        </div>
        <a href={l.telegramLink} target="_blank" rel="noreferrer" className="btn btn-primary">
          <Icon name="plane" size={16} />
          Открыть в Telegram
        </a>
      </div>

      <StatusSteps lead={l} statuses={statuses} />

      <div className="lead-columns">
        <div>
          <TagsSection lead={l} tags={tags} />
          <DataSection lead={l} />
          <HistorySection leadId={l.id} statuses={statuses} tags={tags} />
        </div>
        <Chat lead={l} />
      </div>
    </>
  )
}

function StatusSteps({ lead, statuses }: { lead: LeadCard; statuses: Status[] }) {
  const { setStatus } = useLeadMutations(lead.id)
  const toast = useToast()
  return (
    <section className="card" aria-label="Статус лида" style={{ padding: '18px 20px', gap: 14 }}>
      <div className="card-head">
        <h2 className="card-title" style={{ fontSize: 15 }}>
          Статус
        </h2>
        <span className="muted only-desktop" style={{ fontSize: 13 }}>
          Нажмите на этап, чтобы перевести лида
        </span>
      </div>
      <div className="steps">
        {statuses.map((s) => {
          const on = s.id === lead.statusId
          const c = STATUS_COLORS[s.color]
          return (
            <button
              key={s.id}
              type="button"
              className="step"
              aria-pressed={on}
              disabled={setStatus.isPending}
              onClick={() => !on && setStatus.mutate(s.id, { onError: toast.error })}
              style={on ? { borderColor: c.dot, background: c.bg, color: c.fg } : undefined}
            >
              <span className="dot" style={{ background: c.dot }} />
              {s.name}
            </button>
          )
        })}
      </div>
    </section>
  )
}

function TagsSection({ lead, tags }: { lead: LeadCard; tags: Tag[] }) {
  const { addTag, removeTag } = useLeadMutations(lead.id)
  const toast = useToast()
  const isAdmin = useMe().data?.role === 'ADMIN'
  const onIds = new Set(lead.tags.map((t) => t.id))
  const off = tags.filter((t) => !onIds.has(t.id))
  return (
    <section className="card" aria-label="Теги" style={{ padding: '18px 20px', gap: 14 }}>
      <h2 className="card-title" style={{ fontSize: 15 }}>
        Теги
      </h2>
      <div className="chips">
        {lead.tags.map((t) => (
          <span key={t.id} className="tag-on">
            <TagLabel name={t.name} color={t.color} />
            <button
              type="button"
              aria-label={`Убрать тег ${t.name}`}
              onClick={() => removeTag.mutate(t.id, { onError: toast.error })}
            >
              ×
            </button>
          </span>
        ))}
        {lead.tags.length === 0 && <span className="muted" style={{ fontSize: 13 }}>Тегов нет</span>}
      </div>
      {(off.length > 0 || isAdmin) && (
        <div className="tag-add-row">
          {off.length > 0 && <span className="field">Добавить:</span>}
          {off.map((t) => (
            <button key={t.id} type="button" className="tag-add" onClick={() => addTag.mutate(t.id, { onError: toast.error })}>
              + <TagLabel name={t.name} color={t.color} />
            </button>
          ))}
          {isAdmin && (
            <Link to="/settings/tags" className="card-link" style={{ marginLeft: 'auto' }}>
              Управлять тегами
            </Link>
          )}
        </div>
      )}
    </section>
  )
}

function DataSection({ lead }: { lead: LeadCard }) {
  const { setManager, setSource } = useLeadMutations(lead.id)
  const managers = useManagers().data ?? []
  const toast = useToast()
  const managerMissing = lead.manager && !managers.some((m) => m.id === lead.manager!.id)
  return (
    <section className="card" aria-label="Данные лида" style={{ padding: '18px 20px', gap: 4 }}>
      <h2 className="card-title" style={{ fontSize: 15, marginBottom: 10 }}>
        Данные
      </h2>
      <div className="data-row">
        <span>Telegram</span>
        <span>
          {lead.username ? `@${lead.username} · ` : ''}ID {formatTelegramId(lead.telegramUserId)}
        </span>
      </div>
      <div className="data-row">
        <span>Телефон</span>
        <span>{lead.phone ? formatPhone(lead.phone) : <span className="muted">не указан</span>}</span>
      </div>
      <div className="data-row">
        <label htmlFor="lead-source">Источник</label>
        <select
          id="lead-source"
          className="inline-select"
          value={lead.source ?? ''}
          disabled={setSource.isPending}
          onChange={(e) => setSource.mutate((e.target.value || null) as LeadSource | null, { onError: toast.error })}
        >
          <option value="">Не указан</option>
          {SOURCES.map((s) => (
            <option key={s.key} value={s.key}>
              {s.label}
            </option>
          ))}
        </select>
      </div>
      <div className="data-row">
        <label htmlFor="lead-manager">Менеджер</label>
        <select
          id="lead-manager"
          className="inline-select"
          value={lead.manager?.id ?? ''}
          disabled={setManager.isPending}
          style={{ color: lead.manager ? undefined : 'var(--warn)' }}
          onChange={(e) => setManager.mutate(e.target.value ? Number(e.target.value) : null, { onError: toast.error })}
        >
          <option value="">Не назначен</option>
          {managerMissing && <option value={lead.manager!.id}>{lead.manager!.displayName}</option>}
          {managers.map((m) => (
            <option key={m.id} value={m.id}>
              {m.displayName}
            </option>
          ))}
        </select>
      </div>
      <div className="data-row">
        <span>Создан</span>
        <span>{formatDateLong(lead.createdAt)}</span>
      </div>
    </section>
  )
}

function eventText(e: LeadEvent) {
  const none = (v: string | undefined, empty: string) => v ?? empty
  switch (e.type) {
    case 'CREATED':
      return 'Лид создан автоматически из Telegram'
    case 'STATUS_CHANGED':
      return `статус ${e.oldValue} → ${e.newValue}`
    case 'MANAGER_CHANGED':
      return `менеджер ${none(e.oldValue, 'не назначен')} → ${none(e.newValue, 'не назначен')}`
    case 'SOURCE_CHANGED':
      return `источник ${none(e.oldValue, 'не указан')} → ${none(e.newValue, 'не указан')}`
    case 'TAG_ADDED':
      return `добавлен тег #${e.newValue}`
    case 'TAG_REMOVED':
      return `снят тег #${e.oldValue}`
  }
}

function eventColor(e: LeadEvent, statuses: Status[], tags: Tag[]) {
  if (e.type === 'STATUS_CHANGED') {
    const s = statuses.find((x) => x.name === e.newValue)
    return s ? STATUS_COLORS[s.color].dot : '#8A919E'
  }
  if (e.type === 'TAG_ADDED' || e.type === 'TAG_REMOVED') {
    const t = tags.find((x) => x.name === (e.newValue ?? e.oldValue))
    return t ? TAG_COLORS[t.color].c : '#8A919E'
  }
  return e.type === 'CREATED' ? '#2F7FD3' : '#8A919E'
}

function HistorySection({ leadId, statuses, tags }: { leadId: number; statuses: Status[]; tags: Tag[] }) {
  const query = useLeadEvents(leadId)
  const events = query.data ?? []
  return (
    <section className="card" aria-label="История" style={{ padding: '18px 20px', gap: 14 }}>
      <h2 className="card-title" style={{ fontSize: 15 }}>
        История изменений
      </h2>
      <div className="history">
        {events.map((e) => (
          <div key={e.id} className="history-item">
            <span className="dot" style={{ background: eventColor(e, statuses, tags) }} />
            <div>
              {e.type !== 'CREATED' && <b>{e.actor?.displayName ?? 'Система'}: </b>}
              {eventText(e)}
              <div className="muted" style={{ marginTop: 2 }}>
                {formatMessageDate(e.createdAt)}
              </div>
            </div>
          </div>
        ))}
        {query.isPending && <span className="muted">Загрузка…</span>}
        {query.isSuccess && events.length === 0 && <span className="muted">Изменений пока нет</span>}
      </div>
    </section>
  )
}

function Chat({ lead }: { lead: LeadCard }) {
  const query = useLeadMessages(lead.id)
  const messages = query.data ?? []
  const { reply } = useLeadMutations(lead.id)
  const toast = useToast()
  const [text, setText] = useState('')
  const body = useRef<HTMLDivElement>(null)

  useEffect(() => {
    body.current?.scrollTo({ top: body.current.scrollHeight })
  }, [messages.length])

  const send = (e?: FormEvent) => {
    e?.preventDefault()
    const value = text.trim()
    if (!value || reply.isPending) return
    reply.mutate(value, { onSuccess: () => setText(''), onError: toast.error })
  }

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      send()
    }
  }

  return (
    <section className="card chat" aria-label="Переписка в Telegram">
      <div className="chat-head">
        <h2 className="card-title" style={{ fontSize: 15 }}>
          Переписка
        </h2>
        <span className="muted" style={{ fontSize: 12 }}>
          синхронизировано с Telegram
        </span>
      </div>
      <div className="chat-body" ref={body}>
        {messages.map((m, i) => (
          <MessageBubble key={m.id} message={m} previous={messages[i - 1]} />
        ))}
        {(query.isPending || messages.length === 0) && (
          <div className="muted" style={{ margin: 'auto', fontSize: 14 }}>
            {query.isPending ? 'Загрузка…' : 'Сообщений пока нет'}
          </div>
        )}
      </div>
      {lead.canReply ? (
        <form className="reply" onSubmit={send}>
          <label htmlFor="reply" className="sr-only">
            Ответ лиду
          </label>
          <textarea
            id="reply"
            rows={1}
            placeholder="Ответить в Telegram…"
            value={text}
            maxLength={4096}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
          />
          <button type="submit" className="send" aria-label="Отправить" disabled={!text.trim() || reply.isPending}>
            <Icon name="send" />
          </button>
        </form>
      ) : (
        <div className="reply-blocked">
          <span>{lead.replyBlockedReason}</span>
          <a href={lead.telegramLink} target="_blank" rel="noreferrer" className="card-link">
            Открыть в Telegram
          </a>
        </div>
      )}
    </section>
  )
}

function MessageBubble({ message: m, previous }: { message: Message; previous?: Message }) {
  const newDay = !previous || new Date(previous.sentAt).toDateString() !== new Date(m.sentAt).toDateString()
  const who = m.direction === 'OUT' ? `${m.author?.displayName ?? 'Аккаунт компании'} · ` : ''
  return (
    <>
      {newDay && <div className="chat-day">{formatDayHeading(m.sentAt)}</div>}
      <div className={`bubble ${m.direction === 'OUT' ? 'out' : 'in'}${m.deleted ? ' deleted' : ''}`}>
        {m.deleted ? `Сообщение удалено: ${m.text}` : m.text}
        <div className="bubble-meta">
          {who}
          {m.edited ? 'изменено · ' : ''}
          {formatTime(m.sentAt)}
        </div>
      </div>
    </>
  )
}

