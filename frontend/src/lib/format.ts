const MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']
const MONTHS_LONG = [
  'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря',
]

const pad = (n: number) => String(n).padStart(2, '0')

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

function isYesterday(d: Date, now: Date) {
  const y = new Date(now)
  y.setDate(now.getDate() - 1)
  return sameDay(d, y)
}

export function formatTime(iso: string) {
  const d = new Date(iso)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** «Сегодня, 12:40», «Вчера, 18:20», «12 окт, 09:05», «3 мая 2025, 07:00». */
export function formatMessageDate(iso: string, now = new Date()) {
  const d = new Date(iso)
  const time = formatTime(iso)
  if (sameDay(d, now)) return `Сегодня, ${time}`
  if (isYesterday(d, now)) return `Вчера, ${time}`
  const day = `${d.getDate()} ${MONTHS_SHORT[d.getMonth()]}`
  return d.getFullYear() === now.getFullYear() ? `${day}, ${time}` : `${day} ${d.getFullYear()}, ${time}`
}

/** «13 октября, 15:47»; другой год — «2 января 2025, 08:00». */
export function formatDateLong(iso: string, now = new Date()) {
  const d = new Date(iso)
  const year = d.getFullYear() === now.getFullYear() ? '' : ` ${d.getFullYear()}`
  return `${d.getDate()} ${MONTHS_LONG[d.getMonth()]}${year}, ${formatTime(iso)}`
}

/** Разделитель дней в переписке: «Сегодня», «Вчера», «1 октября». */
export function formatDayHeading(iso: string, now = new Date()) {
  const d = new Date(iso)
  if (sameDay(d, now)) return 'Сегодня'
  if (isYesterday(d, now)) return 'Вчера'
  const year = d.getFullYear() === now.getFullYear() ? '' : ` ${d.getFullYear()}`
  return `${d.getDate()} ${MONTHS_LONG[d.getMonth()]}${year}`
}

/** Короткая подпись дня для графика: «14 окт». */
export function formatShortDay(isoDate: string) {
  const [, m, d] = isoDate.split('-').map(Number)
  return `${d} ${MONTHS_SHORT[m - 1]}`
}

/** Время ответа: «45 сек», «11 мин», «2 ч 5 мин»; null — «—». */
export function formatDuration(seconds: number | null | undefined) {
  if (seconds == null) return '—'
  if (seconds < 60) return `${Math.round(seconds)} сек`
  if (seconds < 3600) return `${Math.round(seconds / 60)} мин`
  const h = Math.floor(seconds / 3600)
  const m = Math.round((seconds % 3600) / 60)
  return m === 0 ? `${h} ч` : `${h} ч ${m} мин`
}

export function plural(n: number, one: string, few: string, many: string) {
  const mod10 = n % 10
  const mod100 = n % 100
  if (mod10 === 1 && mod100 !== 11) return one
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few
  return many
}

export function initials(name: string) {
  const words = name.replace(/^@/, '').split(/\s+/).filter(Boolean)
  return words
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase()
}

export function formatTelegramId(id: number) {
  return String(id).replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
}

/** Дата в местном часовом поясе в формате YYYY-MM-DD. */
export function localDateISO(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function addDays(d: Date, days: number) {
  const r = new Date(d)
  r.setDate(r.getDate() + days)
  return r
}

/** Российские номера — «+7 900 000-00-00», остальные как есть. */
export function formatPhone(phone: string) {
  const m = phone.match(/^\+7(\d{3})(\d{3})(\d{2})(\d{2})$/)
  return m ? `+7 ${m[1]} ${m[2]}-${m[3]}-${m[4]}` : phone
}
