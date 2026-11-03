// @stories EO15  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { describe, expect, it } from 'vitest'
import { formatRelativeTime } from './relativeTime'

const NOW = new Date('2026-09-19T12:00:00Z')

describe('formatRelativeTime', () => {
  it('EO15 (boundary): says "just now" for anything under a minute old', () => {
    expect(formatRelativeTime('2026-09-19T11:59:30Z', NOW)).toBe('just now')
  })

  it('EO15 (happy): reports whole minutes, singular vs plural', () => {
    expect(formatRelativeTime('2026-09-19T11:59:00Z', NOW)).toBe('1 minute ago')
    expect(formatRelativeTime('2026-09-19T11:55:00Z', NOW)).toBe('5 minutes ago')
  })

  it('EO15 (boundary): reports whole hours once past 60 minutes', () => {
    expect(formatRelativeTime('2026-09-19T10:00:00Z', NOW)).toBe('2 hours ago')
  })

  it('EO15 (boundary): exactly one hour is singular', () => {
    expect(formatRelativeTime('2026-09-19T11:00:00Z', NOW)).toBe('1 hour ago')
  })

  it('EO15 (boundary): reports whole days once past 24 hours', () => {
    expect(formatRelativeTime('2026-09-17T12:00:00Z', NOW)).toBe('2 days ago')
  })

  it('EO15 (boundary): falls back to a plain date once past 30 days', () => {
    const farInThePast = '2026-01-01T12:00:00Z'
    expect(formatRelativeTime(farInThePast, NOW)).toBe(new Date(farInThePast).toLocaleDateString())
  })
})
