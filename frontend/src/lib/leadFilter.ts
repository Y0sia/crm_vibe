/** Фильтры списка лидов. Хранятся в адресной строке, чтобы возврат из карточки их сохранял. */
export type LeadFilters = {
  q: string
  messageText: string
  from: string
  to: string
  /** 'all' | 'none' | id менеджера */
  manager: string
  /** 'all' | 'none' | ключ источника */
  source: string
  statusIds: number[]
  tagIds: number[]
}

export const EMPTY_FILTERS: LeadFilters = {
  q: '',
  messageText: '',
  from: '',
  to: '',
  manager: 'all',
  source: 'all',
  statusIds: [],
  tagIds: [],
}

const ids = (values: string[]) => values.map(Number).filter((n) => Number.isFinite(n) && n > 0)

export function parseFilters(search: URLSearchParams): LeadFilters {
  return {
    q: search.get('q') ?? '',
    messageText: search.get('messageText') ?? '',
    from: search.get('from') ?? '',
    to: search.get('to') ?? '',
    manager: search.get('manager') ?? 'all',
    source: search.get('source') ?? 'all',
    statusIds: ids(search.getAll('statusIds')),
    tagIds: ids(search.getAll('tagIds')),
  }
}

export function parsePage(search: URLSearchParams) {
  const page = Number(search.get('page') ?? 0)
  return Number.isInteger(page) && page > 0 ? page : 0
}

function appendFilters(p: URLSearchParams, f: LeadFilters) {
  if (f.q.trim()) p.set('q', f.q.trim())
  if (f.messageText.trim()) p.set('messageText', f.messageText.trim())
  if (f.from) p.set('from', f.from)
  if (f.to) p.set('to', f.to)
  if (f.manager !== 'all') p.set('manager', f.manager)
  if (f.source !== 'all') p.set('source', f.source)
  f.statusIds.forEach((id) => p.append('statusIds', String(id)))
  f.tagIds.forEach((id) => p.append('tagIds', String(id)))
}

export function filtersToSearch(f: LeadFilters, page: number) {
  const p = new URLSearchParams()
  appendFilters(p, f)
  if (page > 0) p.set('page', String(page))
  return p
}

export function toApiParams(f: LeadFilters, page: number, size: number) {
  const p = new URLSearchParams()
  appendFilters(p, f)
  p.set('page', String(page))
  p.set('size', String(size))
  return p
}

/** Сколько фильтров включено — как бейдж на кнопке «Фильтр» в макете (поиск по имени не считается). */
export function countActive(f: LeadFilters) {
  return [
    f.statusIds.length > 0,
    f.tagIds.length > 0,
    f.messageText.trim() !== '',
    f.from !== '' || f.to !== '',
    f.manager !== 'all',
    f.source !== 'all',
  ].filter(Boolean).length
}
