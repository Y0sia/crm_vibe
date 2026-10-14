import type { LeadSource, StatusColor, TagColor } from '../api/types'

/** Цвета статусов из макета: точка, фон и текст «пилюли». */
export const STATUS_COLORS: Record<StatusColor, { dot: string; bg: string; fg: string; label: string }> = {
  BLUE: { dot: '#2F7FD3', bg: '#E4EFFB', fg: '#1A5FA8', label: 'синий' },
  ORANGE: { dot: '#E07B24', bg: '#FDEBD8', fg: '#8F4209', label: 'оранжевый' },
  PURPLE: { dot: '#8B63D9', bg: '#EDE6FA', fg: '#5B3BA6', label: 'фиолетовый' },
  TEAL: { dot: '#1F9E92', bg: '#DCF1EE', fg: '#13665F', label: 'бирюзовый' },
  GREEN: { dot: '#36A04A', bg: '#E0F2E3', fg: '#21692D', label: 'зелёный' },
  PINK: { dot: '#D6409F', bg: '#FBE4F2', fg: '#94206A', label: 'розовый' },
  YELLOW: { dot: '#C9A21A', bg: '#FAF1D2', fg: '#715A08', label: 'жёлтый' },
  GRAY: { dot: '#8A919E', bg: '#ECEEF1', fg: '#4D5462', label: 'серый' },
}

export const TAG_COLORS: Record<TagColor, { c: string; label: string }> = {
  BRICK: { c: '#C2410C', label: 'кирпичный' },
  BLUE: { c: '#1E64AE', label: 'синий' },
  PURPLE: { c: '#7C3AED', label: 'фиолетовый' },
  GREEN: { c: '#15803D', label: 'зелёный' },
  RED: { c: '#B91C1C', label: 'красный' },
  TEAL: { c: '#0F766E', label: 'бирюзовый' },
  MUSTARD: { c: '#A16207', label: 'горчичный' },
  GRAPHITE: { c: '#4D5462', label: 'графит' },
}

export const SOURCES: { key: LeadSource; label: string; color: string }[] = [
  { key: 'CHANNEL', label: 'Канал', color: '#E07B24' },
  { key: 'GROUP', label: 'Группа', color: '#1F9E92' },
  { key: 'ADS', label: 'Реклама', color: '#8B63D9' },
  { key: 'REFERRAL', label: 'Сарафан', color: '#2B85DA' },
  { key: 'OTHER', label: 'Другое', color: '#C9A21A' },
]

export const NO_SOURCE = { label: 'Не указан', color: '#C4C9D2' }

export function sourceLabel(source?: LeadSource | null) {
  return SOURCES.find((s) => s.key === source)?.label ?? NO_SOURCE.label
}

export function sourceColor(source?: LeadSource | null) {
  return SOURCES.find((s) => s.key === source)?.color ?? NO_SOURCE.color
}

const AVATAR = ['#8B63D9', '#2F7FD3', '#1F9E92', '#E07B24', '#D6409F', '#36A04A', '#C9A21A']

export function avatarColor(id: number) {
  return AVATAR[Math.abs(id) % AVATAR.length]
}
