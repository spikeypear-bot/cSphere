// Shared fetch wrapper. Handles the four response shapes DEV11's acceptance
// criteria call for: success, validation error (422, with missingFields),
// unauthorised/expired-session, and unexpected failure — consistently, so
// every feature doesn't reinvent error handling.
//
// It also owns the access-token lifecycle: attaching the Bearer header,
// renewing a token that has expired mid-session, and retrying the call that
// tripped over it. Callers never see that happen.

import {
  clearTokens,
  readTokens,
  tokensFromResponse,
  writeTokens,
  type AuthTokens,
  type LoginResponse,
} from './authTokens'

// Base URL is read once from Vite's env (see README "Environment
// configuration"). Defaults to the relative `/api` path, which `vite.config.ts`
// proxies to the local backend in dev and which a production reverse proxy
// can serve from the same origin — set VITE_API_BASE_URL only when the
// frontend and backend are *not* on the same origin (e.g. a separately
// deployed backend).
const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '')

/** Dispatched on `window` when the session is over and cannot be renewed, so
 * any part of the app (currently: SessionProvider) can react — e.g. by
 * clearing a stale session — without the API client needing to know about
 * session state itself.
 *
 * A 403 does NOT dispatch this. Being forbidden means the session is perfectly
 * valid and this account simply may not do that thing (AU06); logging the user
 * out over it would be both wrong and baffling. */
export const UNAUTHORISED_EVENT = 'connectsphere:unauthorised'

export class ApiClientError extends Error {
  readonly status: number
  readonly missingFields: string[] | null

  constructor(message: string, status: number, missingFields: string[] | null = null) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.missingFields = missingFields
  }

  /** The session is gone or unrenewable — the caller must log in again. */
  get isUnauthorised(): boolean {
    return this.status === 401
  }

  /** Authenticated, but this account isn't allowed to do this. */
  get isForbidden(): boolean {
    return this.status === 403
  }
}

interface ApiErrorBody {
  message?: string
  missingFields?: string[] | null
}

async function parse(response: Response): Promise<unknown> {
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

function failed(response: Response, json: unknown): ApiClientError {
  const body = (json ?? {}) as ApiErrorBody
  if (response.status === 401 || response.status === 403) {
    return new ApiClientError(
      body.message ??
        (response.status === 403
          ? 'You do not have permission to do that.'
          : 'Your session has expired. Please log in again.'),
      response.status,
    )
  }
  return new ApiClientError(
    body.message ?? `Request failed (${response.status})`,
    response.status,
    body.missingFields ?? null,
  )
}

function unreachable(): ApiClientError {
  return new ApiClientError(
    'Could not reach ConnectSphere. Check your connection and try again.',
    0,
  )
}

// --- /api/auth: deliberately NOT routed through request() -------------------
//
// These three carry their own credential in the body and must go out with no
// Authorization header at all. If an expired access token were attached,
// BearerTokenAuthenticationFilter would reject the call with 401 *before*
// SecurityConfig's permitAll rule is ever consulted — so refresh would fail
// in exactly the situation it exists to handle. They also must not recurse
// into the refresh-and-retry logic below.
async function callAuthEndpoint<T>(path: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw unreachable()
  }
  const json = await parse(response)
  if (!response.ok) throw failed(response, json)
  return json as T
}

export async function login(username: string, password: string): Promise<AuthTokens> {
  const response = await callAuthEndpoint<LoginResponse>('/auth/login', { username, password })
  const tokens = tokensFromResponse(response)
  if (!tokens) {
    throw new ApiClientError('This account has a role this app does not recognise.', 0)
  }
  writeTokens(tokens)
  return tokens
}

/** Always resolves: a failed logout still ends the session on this device. */
export async function logout(): Promise<void> {
  const refreshToken = readTokens()?.refreshToken
  clearTokens()
  if (!refreshToken) return
  try {
    await callAuthEndpoint<void>('/auth/logout', { refreshToken })
  } catch {
    // The server-side revoke didn't happen (offline, already revoked). The
    // credentials are gone from this browser either way, which is what the
    // person asked for.
  }
}

// One renewal at a time. Several requests can 401 together when a token
// expires; without this each would spend the single-use refresh token, and
// every one after the first would fail against a token the server has already
// rotated away — and rotating a spent token revokes the whole family.
let inFlightRenewal: Promise<AuthTokens | null> | null = null

function renewSession(): Promise<AuthTokens | null> {
  inFlightRenewal ??= (async () => {
    const refreshToken = readTokens()?.refreshToken
    if (!refreshToken) return null
    try {
      const response = await callAuthEndpoint<LoginResponse>('/auth/refresh', { refreshToken })
      const tokens = tokensFromResponse(response)
      if (tokens) writeTokens(tokens)
      return tokens
    } catch {
      return null
    }
  })().finally(() => {
    inFlightRenewal = null
  })
  return inFlightRenewal
}

function endSession(): void {
  clearTokens()
  window.dispatchEvent(new CustomEvent(UNAUTHORISED_EVENT))
}

interface RequestOptions {
  body?: unknown
}

async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
  renewed = false,
): Promise<T> {
  const headers: Record<string, string> = {}
  const accessToken = readTokens()?.accessToken
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    // Network failure (backend unreachable, offline, etc.) — distinguish this
    // from a real HTTP error status so the UI can say "couldn't reach the
    // server" rather than a misleading validation message.
    throw unreachable()
  }

  if (response.status === 401 && !renewed) {
    // Most likely just an expired access token. Renew once and replay the
    // original call; the person never sees it. `renewed` bounds this to a
    // single attempt, so a genuinely dead session cannot loop.
    const tokens = await renewSession()
    if (tokens) return request<T>(method, path, options, true)
  }

  if (response.status === 204) return undefined as T

  const json = await parse(response)
  if (!response.ok) {
    if (response.status === 401) endSession()
    throw failed(response, json)
  }
  return json as T
}

export const apiClient = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, { body: body ?? {} }),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, { body }),
  del: <T>(path: string) => request<T>('DELETE', path),
}
