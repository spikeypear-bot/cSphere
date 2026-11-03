// @stories EC-NEW3
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useRefreshingResource } from './useRefreshingResource'

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

afterEach(() => vi.useRealTimers())

describe('EC-NEW3 useRefreshingResource — reads that fail, or arrive too late', () => {
  it('EC-NEW3 (conflict): a slow older read that fails cannot replace a newer result with an error', async () => {
    vi.useFakeTimers()
    const old = deferred<string>(), latest = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const hook = renderHook(() => useRefreshingResource<string>(load, 'event'))
    await act(async () => { vi.advanceTimersByTime(5000) })
    await act(async () => { latest.resolve('approved 150') })
    await act(async () => { old.reject(new Error('Down')) })
    expect(hook.result.current.data).toBe('approved 150')
    expect(hook.result.current.error).toBeNull()
  })

  it('EC-NEW3 (failure): a failure with no message still says the information could not be loaded', async () => {
    const hook = renderHook(() => useRefreshingResource<string>(() => Promise.reject('offline'), 'event'))
    await waitFor(() => expect(hook.result.current.error).toBe('Could not load this information.'))
    expect(hook.result.current.cause).toBe('offline')
    expect(hook.result.current.data).toBeNull()
  })

  it('EC-NEW3 (boundary): with nothing selected nothing loads, and a local update is ignored', () => {
    const load = vi.fn(() => Promise.resolve('x'))
    const hook = renderHook(() => useRefreshingResource<string>(load, null))
    act(() => hook.result.current.setData('local'))
    expect(hook.result.current).toMatchObject({ data: null, loading: false })
    expect(load).not.toHaveBeenCalled()
  })

  it('EC-NEW3 (boundary): an update made before anything has loaded, or after a failed load, starts from nothing', async () => {
    const pending = deferred<number>()
    const before = renderHook(() => useRefreshingResource<number>(() => pending.promise, 'count'))
    const seenBefore = vi.fn((current: number | null) => (current ?? 0) + 1)
    act(() => before.result.current.setData(seenBefore))
    expect(seenBefore).toHaveBeenCalledWith(null)
    expect(before.result.current.data).toBe(1)

    const failed = renderHook(() => useRefreshingResource<number>(() => Promise.reject(new Error('Down')), 'count'))
    await waitFor(() => expect(failed.result.current.error).toBe('Down'))
    const seenAfter = vi.fn((current: number | null) => (current ?? 0) + 5)
    act(() => failed.result.current.setData(seenAfter))
    expect(seenAfter).toHaveBeenCalledWith(null)
    expect(failed.result.current.data).toBe(5)
  })
})
