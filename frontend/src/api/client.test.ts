import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, setUnauthorizedHandler } from './client'

function respond(status: number, body?: unknown) {
  return vi.fn().mockResolvedValue(
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    }),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('api client', () => {
  it('GET возвращает JSON', async () => {
    const fetchMock = respond(200, { id: 1 })
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.get('/api/leads/1')).resolves.toEqual({ id: 1 })
    expect(fetchMock).toHaveBeenCalledWith('/api/leads/1', expect.objectContaining({ method: 'GET', credentials: 'same-origin' }))
  })

  it('POST шлёт JSON и CSRF-токен из cookie', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123; path=/'
    const fetchMock = respond(201, { ok: true })
    vi.stubGlobal('fetch', fetchMock)
    await api.post('/api/tags', { name: 'VIP' })
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = new Headers(init.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('abc-123')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init.body).toBe('{"name":"VIP"}')
  })

  it('204 — без тела', async () => {
    vi.stubGlobal('fetch', respond(204))
    await expect(api.delete('/api/tags/1')).resolves.toBeUndefined()
  })

  it('ошибка API с сообщением и полями', async () => {
    vi.stubGlobal('fetch', respond(400, { message: 'Проверьте заполнение полей', fields: { name: 'Введите название' } }))
    const error = await api.post('/api/tags', {}).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(400)
    expect((error as ApiError).message).toBe('Проверьте заполнение полей')
    expect((error as ApiError).fields).toEqual({ name: 'Введите название' })
  })

  it('ошибка без JSON — общее сообщение', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('oops', { status: 500 })))
    const error = (await api.get('/api/x').catch((e: unknown) => e)) as ApiError
    expect(error.message).toBe('Сервер не отвечает. Попробуйте ещё раз.')
  })

  it('401 вызывает обработчик выхода', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', respond(401))
    await expect(api.get('/api/leads')).rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledOnce()
    setUnauthorizedHandler(null)
  })
})
