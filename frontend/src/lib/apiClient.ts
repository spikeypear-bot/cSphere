// Shared fetch wrapper. Handles the four response shapes DEV11's acceptance
// criteria call for: success, validation error (422, with missingFields),
// unauthorised/expired-session (not applicable yet — no real auth, see
// docs/decision-log.md D6a/Q2), and unexpected failure — consistently, so
// every feature doesn't reinvent error handling.

export class ApiClientError extends Error {
  readonly status: number
  readonly missingFields: string[] | null

  constructor(message: string, status: number, missingFields: string[] | null = null) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.missingFields = missingFields
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
    response = await fetch(`/api${path}`, {
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
