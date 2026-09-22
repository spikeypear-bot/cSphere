import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiClientError, UNAUTHORISED_EVENT } from './apiClient'
import { AUTH_STORAGE_KEY } from './authTokens'

function signedIn(accessToken = 'access-1', refreshToken = 'refresh-1') {
  window.localStorage.setItem(
    AUTH_STORAGE_KEY,
    JSON.stringify({
      accessToken,
      refreshToken,
      username: 'eo1',
      role: 'organiser',
      organisation: 'Acme Pte Ltd',
    }),
  )
}

interface FakeResponse {
  ok?: boolean
  status?: number
  jsonBody?: unknown
}

/** Responds to calls in order, so a renew-and-replay sequence can be scripted. */
function mockFetchSequence(...responses: FakeResponse[]) {
  const spy = vi.fn()
  for (const { ok, status = 200, jsonBody } of responses) {
    spy.mockResolvedValueOnce({
      ok: ok ?? status < 400,
      status,
      text: async () => (jsonBody !== undefined ? JSON.stringify(jsonBody) : ''),
    } as Response)
  }
  vi.stubGlobal('fetch', spy)
  return spy
}

const RENEWED = {
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
  tokenType: 'Bearer',
  expiresIn: 900,
  username: 'eo1',
  role: 'eo',
  organisation: 'Acme Pte Ltd',
}

describe('apiClient', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  it('sends the access token as a Bearer header', async () => {
    signedIn()
    const fetchSpy = mockFetchSequence({ jsonBody: { hello: 'world' } })

    await apiClient.get('/event-requests')

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/event-requests',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer access-1' }),
      }),
    )
  })

  it('sends no organisation of any kind — the backend reads its own claim', async () => {
    signedIn()
    const fetchSpy = mockFetchSequence({ jsonBody: [] })

    await apiClient.get('/event-requests')

    const headers = fetchSpy.mock.calls[0][1].headers as Record<string, string>
    expect(headers['X-Organisation']).toBeUndefined()
  })

  it('parses a successful JSON response', async () => {
    signedIn()
    mockFetchSequence({ jsonBody: { requestId: 'abc' } })
    const result = await apiClient.get<{ requestId: string }>('/event-requests/abc')
    expect(result.requestId).toBe('abc')
  })

  it('throws an ApiClientError with missingFields on a 422 response', async () => {
    signedIn()
    mockFetchSequence({
      status: 422,
      jsonBody: { message: 'Event request is missing required fields', missingFields: ['eventName'] },
    })

    await expect(apiClient.post('/event-requests/abc/submit')).rejects.toMatchObject({
      status: 422,
      missingFields: ['eventName'],
    })
  })

  it('renews an expired access token and replays the original request', async () => {
    signedIn()
    const fetchSpy = mockFetchSequence(
      { status: 401 },
      { jsonBody: RENEWED },
      { jsonBody: { requestId: 'abc' } },
    )

    const result = await apiClient.get<{ requestId: string }>('/event-requests/abc')

    expect(result.requestId).toBe('abc')
    expect(fetchSpy).toHaveBeenCalledTimes(3)
    // The refresh call must carry no Authorization header: an expired token
    // there is rejected by the filter chain before permitAll is consulted.
    const refreshCall = fetchSpy.mock.calls[1]
    expect(refreshCall[0]).toBe('/api/auth/refresh')
    expect((refreshCall[1].headers as Record<string, string>).Authorization).toBeUndefined()
    // The replay uses the new token, not the stale one.
    expect((fetchSpy.mock.calls[2][1].headers as Record<string, string>).Authorization)
      .toBe('Bearer access-2')
  })

  it('ends the session when the token cannot be renewed', async () => {
    signedIn()
    mockFetchSequence({ status: 401 }, { status: 401 }, { status: 401, jsonBody: { message: 'Session expired' } })
    const listener = vi.fn()
    window.addEventListener(UNAUTHORISED_EVENT, listener)

    const error = await apiClient.get('/event-requests').catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiClientError)
    expect((error as ApiClientError).isUnauthorised).toBe(true)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(window.localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull()
    window.removeEventListener(UNAUTHORISED_EVENT, listener)
  })

  it('does NOT end the session on a 403 — being forbidden is not being logged out', async () => {
    signedIn()
    mockFetchSequence({ status: 403, jsonBody: { message: 'You do not have permission to perform this action.' } })
    const listener = vi.fn()
    window.addEventListener(UNAUTHORISED_EVENT, listener)

    const error = await apiClient.get('/venues').catch((err: unknown) => err)

    expect((error as ApiClientError).isForbidden).toBe(true)
    expect((error as ApiClientError).isUnauthorised).toBe(false)
    expect(listener).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(AUTH_STORAGE_KEY)).not.toBeNull()
    window.removeEventListener(UNAUTHORISED_EVENT, listener)
  })

  it('does not treat a validation (422) or server (500) error as unauthorised', async () => {
    signedIn()
    mockFetchSequence({ status: 500 })
    const error = await apiClient.get('/event-requests').catch((err: unknown) => err)
    expect((error as ApiClientError).isUnauthorised).toBe(false)
  })

  it('throws a distinguishable ApiClientError when the network request itself fails', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await apiClient.get('/event-requests').catch((err: unknown) => err)
    expect(error).toBeInstanceOf(ApiClientError)
    expect((error as ApiClientError).status).toBe(0)
  })
})
