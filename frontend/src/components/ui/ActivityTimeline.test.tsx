import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { ActivityTimeline } from './ActivityTimeline'
import type { ActivityDto } from '../../types/activity'

const entries: ActivityDto[] = [
  { activityId: 'a1', type: 'submitted', actorName: 'eo1', actorRole: 'eo', message: null, flaggedFields: [],
    fromStatus: 'draft', toStatus: 'pending', occurredAt: '2026-09-20T01:00:00Z' },
  { activityId: 'a2', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec',
    message: 'Is 150 final?', flaggedFields: ['expectedAttendance', 'endDatetime'],
    fromStatus: 'pending', toStatus: 'clarification_required', occurredAt: '2026-09-21T01:00:00Z' },
]

describe('ActivityTimeline', () => {
  afterEach(cleanup)

  it('renders each entry in the order given, with who acted and what they did', () => {
    render(<ActivityTimeline entries={entries} />)
    const items = within(screen.getByRole('list', { name: 'Request timeline' })).getAllByRole('listitem')
      .filter((li) => li.classList.contains('activity-timeline__entry'))

    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('eo1 (Event Organiser) submitted the request')
    expect(items[1]).toHaveTextContent('ec1 (Event Coordinator) asked for clarification')
  })

  it('shows the message, the flagged fields by name, and the status change', () => {
    render(<ActivityTimeline entries={entries} />)

    expect(screen.getByText('Is 150 final?').tagName).toBe('BLOCKQUOTE')
    const flags = screen.getByRole('list', { name: 'Fields that need attention' })
    expect(within(flags).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Expected attendance', 'End date & time'])
    const statuses = [...document.querySelectorAll('.activity-timeline__status')].map((p) => p.textContent)
    expect(statuses).toEqual(['Draft → Submitted', 'Submitted → Clarification required'])
  })

  it('shows the empty text when there is no history', () => {
    render(<ActivityTimeline entries={[]} emptyText="No history yet." />)
    expect(screen.getByText('No history yet.')).toBeInTheDocument()
  })
})
