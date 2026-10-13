import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StatusView } from '../../types/status'
import { JourneyStatusBadge, JourneyStepper, StatusFilter } from './JourneyStatus'

const view = (code: StatusView['code'], label: string): StatusView => ({
  code, label, explanation: `${label} explained.`, nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false,
})

afterEach(cleanup)

describe('JourneyStatus (EC-NEW2)', () => {
  it('shows the label with its explanation as a tooltip', () => {
    render(<JourneyStatusBadge status={view('PLANNING', 'Planning')} />)
    expect(screen.getByText('Planning')).toHaveAttribute('title', 'Planning explained.')
  })

  it('marks the current step and the completed ones', () => {
    render(<JourneyStepper status={view('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review')} />)
    const steps = screen.getAllByRole('listitem')
    expect(steps.map((s) => s.getAttribute('data-state'))).toEqual(['done', 'done', 'done', 'current', 'upcoming'])
    expect(steps[3]).toHaveAttribute('aria-current', 'step')
  })

  it('flags steps that need attention and ends the line on rejection', () => {
    const { rerender } = render(<JourneyStepper status={view('SAFETY_CHANGES_REQUESTED', 'Safety Changes Requested')} />)
    expect(screen.getAllByRole('listitem')[3]).toHaveAttribute('data-state', 'attention')
    rerender(<JourneyStepper status={view('REJECTED', 'Rejected')} />)
    expect(screen.getAllByRole('listitem').at(-1)).toHaveAttribute('data-state', 'ended')
  })

  it('filters by status with counts, and hides itself when there is only one status', async () => {
    const onChange = vi.fn()
    const { rerender } = render(<StatusFilter selected={null} onChange={onChange}
      statuses={[view('PLANNING', 'Planning'), view('PLANNING', 'Planning'), view('CONFIRMED', 'Confirmed')]} />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Planning 2' }))
    expect(onChange).toHaveBeenCalledWith('PLANNING')
    expect(screen.getByRole('button', { name: 'All 3' })).toHaveAttribute('aria-pressed', 'true')

    rerender(<StatusFilter selected={null} onChange={onChange} statuses={[view('PLANNING', 'Planning')]} />)
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
  })
})
