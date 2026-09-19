import { describe, expect, it } from 'vitest'
import { formatRelativeTime } from './relativeTime'

const NOW = new Date('2026-09-19T12:00:00Z')

describe('formatRelativeTime', () => {
  it('says "just now" for anything under a minute old', () => {
    expect(formatRelativeTime('2026-09-19T11:59:30Z', NOW)).toBe('just now')
  })

  it('reports whole minutes, singular vs plural', () => {
    expect(formatRelativeTime('2026-09-19T11:59:00Z', NOW)).toBe('1 minute ago')
    expect(formatRelativeTime('2026-09-19T11:55:00Z', NOW)).toBe('5 minutes ago')
  })

  it('reports whole hours once past 60 minutes', () => {
    expect(formatRelativeTime('2026-09-19T10:00:00Z', NOW)).toBe('2 hours ago')
  })

  it('reports whole days once past 24 hours', () => {
    expect(formatRelativeTime('2026-09-17T12:00:00Z', NOW)).toBe('2 days ago')
  })

  it('falls back to a plain date once past 30 days', () => {
    const farInThePast = '2026-01-01T12:00:00Z'
    expect(formatRelativeTime(farInThePast, NOW)).toBe(new Date(farInThePast).toLocaleDateString())
  })
})
