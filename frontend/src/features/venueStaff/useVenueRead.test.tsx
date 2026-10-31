// @stories DEV16 VS18
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import { apiClient } from '../../lib/apiClient'
import { useVenueRead } from './useVenueRead'

vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(), apiClient: { get: vi.fn() } }))
afterEach(() => { cleanup(); vi.resetAllMocks() })
describe('DEV16 venue read request ordering', () => {
  it('DEV16 (conflict): an old read failure cannot replace newer saved data or expose a stale error', async () => {
    let reject!: (cause: unknown) => void
    vi.mocked(apiClient.get).mockReturnValue(new Promise((_, fail) => { reject = fail }))
    const { result } = renderHook(() => useVenueRead<{ name: string }>('/venues/room'))
    act(() => result.current.replaceData({ name: 'Saved room' }))
    await act(async () => { reject(new Error('Old request failed')) })
    expect(result.current.data).toEqual({ name: 'Saved room' })
    expect(result.current.error).toBeUndefined()
    expect(result.current.loading).toBe(false)
  })
})
