import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiClientError } from './apiClient'

function mockFetchOnce(response: Partial<Response> & { jsonBody?: unknown }) {
  const { jsonBody, ...rest } = response
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => (jsonBody !== undefined ? JSON.stringify(jsonBody) : ''),
      ...rest,
    } as Response),
  )
}

describe('apiClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the organisation as an X-Organisation header', async () => {
    const fetchSpy = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ hello: 'world' }),
    } as Response)
    vi.stubGlobal('fetch', fetchSpy)

    await apiClient.get('/event-requests', 'Acme Conferences')

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/event-requests',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Organisation': 'Acme Conferences' }),
      }),
    )
  })

  it('parses a successful JSON response', async () => {
    mockFetchOnce({ jsonBody: { requestId: 'abc' } })
    const result = await apiClient.get<{ requestId: string }>('/event-requests/abc', 'Acme')
    expect(result.requestId).toBe('abc')
  })

  it('throws an ApiClientError with missingFields on a 422 response', async () => {
    mockFetchOnce({
      ok: false,
      status: 422,
      jsonBody: { message: 'Event request is missing required fields', missingFields: ['eventName'] },
    })

    await expect(apiClient.post('/event-requests/abc/submit', {}, 'Acme')).rejects.toMatchObject({
      status: 422,
      missingFields: ['eventName'],
    })
  })

  it('throws a distinguishable ApiClientError when the network request itself fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    )

    const error = await apiClient.get('/event-requests', 'Acme').catch((err: unknown) => err)
    expect(error).toBeInstanceOf(ApiClientError)
    expect((error as ApiClientError).status).toBe(0)
  })
})
