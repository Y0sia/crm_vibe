import { describe, expect, it } from 'vitest'
import { EMPTY_FILTERS, countActive, filtersToSearch, parseFilters, toApiParams } from './leadFilter'

describe('фильтр лидов', () => {
  it('пустой фильтр не попадает в URL', () => {
    expect(filtersToSearch(EMPTY_FILTERS, 0).toString()).toBe('')
  })

  it('туда и обратно через URL', () => {
    const f = {
      ...EMPTY_FILTERS,
      q: 'анна',
      messageText: 'прайс',
      from: '2026-10-01',
      to: '2026-10-14',
      manager: 'none',
      source: 'CHANNEL',
      statusIds: [1, 3],
      tagIds: [5],
    }
    const search = filtersToSearch(f, 2)
    expect(parseFilters(search)).toEqual(f)
    expect(search.get('page')).toBe('2')
  })

  it('параметры API', () => {
    const p = toApiParams({ ...EMPTY_FILTERS, manager: '7', statusIds: [1, 2] }, 1, 25)
    expect(p.get('manager')).toBe('7')
    expect(p.getAll('statusIds')).toEqual(['1', '2'])
    expect(p.get('page')).toBe('1')
    expect(p.get('size')).toBe('25')
    expect(toApiParams(EMPTY_FILTERS, 0, 25).has('manager')).toBe(false)
  })

  it('счётчик активных фильтров как в макете (поиск по имени не считается)', () => {
    expect(countActive(EMPTY_FILTERS)).toBe(0)
    expect(countActive({ ...EMPTY_FILTERS, q: 'x' })).toBe(0)
    expect(
      countActive({ ...EMPTY_FILTERS, statusIds: [1], tagIds: [2], messageText: 'a', from: '2026-01-01', manager: 'none', source: 'ADS' }),
    ).toBe(6)
  })
})
