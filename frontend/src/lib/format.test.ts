import { describe, expect, it } from 'vitest'
import {
  formatDateLong,
  formatDayHeading,
  formatDuration,
  formatMessageDate,
  formatPhone,
  formatTelegramId,
  formatTime,
  initials,
  localDateISO,
  plural,
} from './format'

const now = new Date(2026, 9, 14, 15, 0) // 14 октября 2026, 15:00 по местному времени

describe('formatMessageDate', () => {
  it('сегодня, вчера, в этом году и в прошлом', () => {
    expect(formatMessageDate(new Date(2026, 9, 14, 12, 40).toISOString(), now)).toBe('Сегодня, 12:40')
    expect(formatMessageDate(new Date(2026, 9, 13, 18, 20).toISOString(), now)).toBe('Вчера, 18:20')
    expect(formatMessageDate(new Date(2026, 9, 12, 9, 5).toISOString(), now)).toBe('12 окт, 09:05')
    expect(formatMessageDate(new Date(2025, 4, 3, 7, 0).toISOString(), now)).toBe('3 мая 2025, 07:00')
  })
})

describe('formatDateLong и formatDayHeading', () => {
  it('полная дата', () => {
    expect(formatDateLong(new Date(2026, 9, 13, 15, 47).toISOString(), now)).toBe('13 октября, 15:47')
    expect(formatDateLong(new Date(2025, 0, 2, 8, 0).toISOString(), now)).toBe('2 января 2025, 08:00')
  })
  it('заголовок дня в переписке', () => {
    expect(formatDayHeading(new Date(2026, 9, 14, 1, 0).toISOString(), now)).toBe('Сегодня')
    expect(formatDayHeading(new Date(2026, 9, 13, 23, 0).toISOString(), now)).toBe('Вчера')
    expect(formatDayHeading(new Date(2026, 9, 1, 12, 0).toISOString(), now)).toBe('1 октября')
  })
  it('время', () => {
    expect(formatTime(new Date(2026, 9, 13, 9, 7).toISOString())).toBe('09:07')
  })
})

describe('formatDuration', () => {
  it('секунды, минуты, часы', () => {
    expect(formatDuration(null)).toBe('—')
    expect(formatDuration(45)).toBe('45 сек')
    expect(formatDuration(660)).toBe('11 мин')
    expect(formatDuration(7500)).toBe('2 ч 5 мин')
    expect(formatDuration(7200)).toBe('2 ч')
    expect(formatDuration(90000)).toBe('25 ч')
  })
})

describe('мелочи', () => {
  it('склонения', () => {
    expect(plural(1, 'лид', 'лида', 'лидов')).toBe('лид')
    expect(plural(3, 'лид', 'лида', 'лидов')).toBe('лида')
    expect(plural(11, 'лид', 'лида', 'лидов')).toBe('лидов')
    expect(plural(21, 'лид', 'лида', 'лидов')).toBe('лид')
    expect(plural(0, 'лид', 'лида', 'лидов')).toBe('лидов')
  })
  it('инициалы', () => {
    expect(initials('Дмитрий Орлов')).toBe('ДО')
    expect(initials('Мария К.')).toBe('МК')
    expect(initials('Администратор')).toBe('А')
    expect(initials('@anna_sm')).toBe('A')
  })
  it('телефон', () => {
    expect(formatPhone('+79000000000')).toBe('+7 900 000-00-00')
    expect(formatPhone('+380501234567')).toBe('+380501234567')
  })
  it('ID Telegram с пробелами', () => {
    expect(formatTelegramId(548213097)).toBe('548 213 097')
  })
  it('локальная дата ISO', () => {
    expect(localDateISO(new Date(2026, 0, 5, 23, 30))).toBe('2026-01-05')
  })
})
