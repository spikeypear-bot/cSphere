import { vi } from 'vitest'

type Role = 'organiser' | 'coordinator' | 'venue-staff'

/** A signed-in session, shaped like what LoginPage stores. */
export function seedSession(role: Role, userId = `${role}-1`) {
  window.localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    userId,
    username: role === 'organiser' ? 'eo1' : role === 'coordinator' ? 'ec1' : 'vs1',
    role,
    organisation: role === 'organiser' ? 'Acme Pte Ltd' : 'ConnectSphere',
  }))
}

export function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

export interface Call {
  method: string
  url: string
  body: unknown
}

/**
 * Stubs fetch with a route table keyed "METHOD /api/path". Every call is
 * recorded so tests can assert exactly what was sent, and an unexpected call
 * fails loudly rather than returning something plausible.
 */
export function stubApi(routes: Record<string, (body: unknown) => Response>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const url = String(input)
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ method, url, body })
    const handler = routes[`${method} ${url}`]
    if (!handler) throw new Error(`Unexpected fetch: ${method} ${url}`)
    return handler(body)
  }))
  return calls
}
