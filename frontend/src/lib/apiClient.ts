// Shared fetch wrapper. Handles the four response shapes DEV11's acceptance
// criteria call for: success, validation error (422, with missingFields),
// unauthorised/expired-session (401/403 — there's no real auth session yet,
// see docs/decision-log.md D6a/Q2, but the shape is handled now so real login
// slots in without every caller changing), and unexpected failure —
// consistently, so every feature doesn't reinvent error handling.

// Base URL is read once from Vite's env (see README "Environment
// configuration"). Defaults to the relative `/api` path, which `vite.config.ts`
// proxies to the local backend in dev and which a production reverse proxy
// can serve from the same origin — set VITE_API_BASE_URL only when the
// frontend and backend are *not* on the same origin (e.g. a separately
// deployed backend).
const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '')

/** Dispatched on `window` whenever a request comes back 401/403, so any part
 * of the app (currently: SessionProvider) can react — e.g. by clearing a
 * stale session — without the API client needing to know about session state
 * itself. */
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

  /** True for a 401/403 — the request was rejected because the caller isn't
   * (or is no longer) authorised, as opposed to a validation or server error. */
  get isUnauthorised(): boolean {
    return this.status === 401 || this.status === 403
  }
}

interface ApiErrorBody {
  message?: string
  missingFields?: string[] | null
}

interface RequestOptions {
  organisation?: string | null
  body?: unknown
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (options.organisation) {
    headers['X-Organisation'] = options.organisation
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

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
    throw new ApiClientError('Could not reach ConnectSphere. Check your connection and try again.', 0)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const json = text ? JSON.parse(text) : null

  if (!response.ok) {
    const body = (json ?? {}) as ApiErrorBody

    if (response.status === 401 || response.status === 403) {
      // Let anything listening (SessionProvider) clear a stale session, then
      // surface a message consistent across every caller rather than each
      // feature writing its own "please log in again" copy.
      window.dispatchEvent(new CustomEvent(UNAUTHORISED_EVENT))
      throw new ApiClientError(
        body.message ?? 'Your session has expired. Please log in again.',
        response.status,
      )
    }

    throw new ApiClientError(
      body.message ?? `Request failed (${response.status})`,
      response.status,
      body.missingFields ?? null,
    )
  }

  return json as T
}

export const apiClient = {
  get: <T>(path: string, organisation?: string | null) =>
    request<T>('GET', path, { organisation }),
  post: <T>(path: string, body: unknown, organisation?: string | null) =>
    request<T>('POST', path, { body, organisation }),
  put: <T>(path: string, body: unknown, organisation?: string | null) =>
    request<T>('PUT', path, { body, organisation }),
}
