/** Ошибка API: сообщение уже на русском и готово к показу пользователю. */
export class ApiError extends Error {
  readonly status: number
  readonly fields: Record<string, string>

  constructor(status: number, message: string, fields: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fields = fields
  }
}

let onUnauthorized: (() => void) | null = null

/** Что делать, если сервер ответил 401 (сессия закончилась). */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Нет связи с сервером. Проверьте подключение.')
  }
  if (response.status === 401) onUnauthorized?.()
  const text = await response.text()
  let data: unknown = undefined
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = undefined
    }
  }
  if (!response.ok) {
    const payload = (data ?? {}) as { message?: string; fields?: Record<string, string> }
    const fallback =
      response.status === 401
        ? 'Войдите в систему'
        : response.status === 403
          ? 'Недостаточно прав'
          : 'Сервер не отвечает. Попробуйте ещё раз.'
    throw new ApiError(response.status, payload.message ?? fallback, payload.fields ?? {})
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
}
