// @stories TS01
import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useResource } from './useResource'

describe('TS01 useResource — what a failed console read says', () => {
  it.each([[new Error('')], ['offline']])('TS01 (failure): a failure with no message (%o) says something went wrong', async (reason) => {
    const hook = renderHook(() => useResource(() => Promise.reject(reason), 'today'))
    await waitFor(() => expect(hook.result.current.error).toBe('Something went wrong.'))
    expect(hook.result.current).toMatchObject({ data: null, loading: false })
  })

  it('TS01 (conflict): a failure for a window no longer shown is not reported against the new one', async () => {
    let failOld!: (reason: unknown) => void
    const old = new Promise<string>((_, reject) => { failOld = reject })
    const hook = renderHook(({ key }) => useResource(() => (key === 'old' ? old : Promise.resolve('new data')), key),
      { initialProps: { key: 'old' } })
    hook.rerender({ key: 'new' })
    await waitFor(() => expect(hook.result.current.data).toBe('new data'))
    failOld(new Error('Down'))
    await old.catch(() => {})
    expect(hook.result.current.error).toBeNull()
    expect(hook.result.current.data).toBe('new data')
  })
})
