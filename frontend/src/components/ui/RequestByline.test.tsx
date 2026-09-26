import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { SessionProvider } from '../../lib/session'
import { seedSession } from '../../test/apiStubs'
import type { ActivityDto } from '../../types/activity'
import { RequestByline } from './RequestByline'

function entry(type: ActivityDto['type'], actorName: string, actorRole: string, occurredAt: string): ActivityDto {
  return { activityId: type, type, actorName, actorRole, message: null, flaggedFields: [], fromStatus: null,
    toStatus: null, occurredAt }
}

const TIMELINE = [
  entry('submitted', 'eo1', 'eo', '2026-09-20T01:00:00Z'),
  entry('clarification_requested', 'ec1', 'ec', '2026-09-21T01:00:00Z'),
]

function renderByline(updatedAt = '2026-09-21T01:00:00Z', timeline = TIMELINE, createdByName?: string) {
  render(<SessionProvider>
    <RequestByline organisation="Acme Pte Ltd" createdAt="2026-09-19T01:00:00Z" updatedAt={updatedAt}
      timeline={timeline} createdByName={createdByName} />
  </SessionProvider>)
  return screen.getByText(/submitted/).textContent
}

describe('RequestByline', () => {
  afterEach(() => {
    cleanup()
    window.localStorage.clear()
  })

  it('names the organiser who submitted and the coordinator who last acted, from the coordinator side', () => {
    seedSession('coordinator') // signed in as ec1
    const text = renderByline()
    expect(text).toMatch(/submitted .+ by eo1 · last updated .+ by you$/)
  })

  it('says "by you" for the organiser who submitted, and names the coordinator', () => {
    seedSession('organiser') // signed in as eo1
    const text = renderByline()
    expect(text).toMatch(/submitted .+ by you · last updated .+ by ec1$/)
  })

  it('credits the organiser side when their saved edits are newer than the last timeline entry', () => {
    seedSession('coordinator')
    expect(renderByline('2026-09-22T01:00:00Z')).toMatch(/last updated .+ by the organiser$/)
    cleanup()
    window.localStorage.clear()
    seedSession('organiser')
    expect(renderByline('2026-09-22T01:00:00Z')).toMatch(/last updated .+ by your organisation$/)
  })

  it('falls back to the creator for requests submitted before the timeline existed', () => {
    seedSession('coordinator')
    expect(renderByline('2026-09-21T01:00:00Z', [], 'eo2')).toMatch(/submitted .+ ago by eo2 · /)
    cleanup()
    window.localStorage.clear()
    seedSession('organiser') // eo1
    expect(renderByline('2026-09-21T01:00:00Z', [], 'eo1')).toMatch(/submitted .+ ago by you · /)
  })

  it('omits names it does not know, for requests older than the timeline', () => {
    seedSession('coordinator')
    expect(renderByline('2026-09-21T01:00:00Z', [])).toMatch(/^Acme Pte Ltd · submitted .+ ago · last updated .+ ago$/)
  })
})
