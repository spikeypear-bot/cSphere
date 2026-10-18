import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StatusView } from '../../types/status'
import { JourneyStatusBadge, JourneyStatusExplainer, JourneyStepper, StatusFilter } from './JourneyStatus'

const view = (code: StatusView['code'], label: string): StatusView => ({
  code, label, explanation: `${label} explained.`, nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false,
})

afterEach(cleanup)

describe('JourneyStatus (EC-NEW2)', () => {
  it('EC-NEW2 AC19, AC20 (cross-cutting): shows the label with its explanation as a tooltip', () => {
    render(<JourneyStatusBadge status={view('PLANNING', 'Planning')} />)
    expect(screen.getByText('Planning')).toHaveAttribute('title', 'Planning explained.')
  })

  it('EC-NEW2 AC1 (happy): marks the current step and the completed ones', () => {
    render(<JourneyStepper status={view('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review')} />)
    const steps = screen.getAllByRole('listitem')
    expect(steps.map((s) => s.getAttribute('data-state'))).toEqual(['done', 'done', 'done', 'current', 'upcoming'])
    expect(steps[3]).toHaveAttribute('aria-current', 'step')
  })

  it('EC-NEW2 AC10, AC29 (boundary): flags steps that need attention and ends the line on rejection', () => {
    const { rerender } = render(<JourneyStepper status={view('SAFETY_CHANGES_REQUESTED', 'Safety Changes Requested')} />)
    expect(screen.getAllByRole('listitem')[3]).toHaveAttribute('data-state', 'attention')
    rerender(<JourneyStepper status={view('REJECTED', 'Rejected')} />)
    expect(screen.getAllByRole('listitem').at(-1)).toHaveAttribute('data-state', 'ended')
  })

  it('EC-NEW2 AC26 (boundary): filters by status with counts, and hides itself when there is only one status', async () => {
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

describe('EC-NEW2 JourneyStatus edge cases (boundary)', () => {
  it('EC-NEW2 AC19 (boundary): a final status says what it means without a "Next" line', () => {
    render(<JourneyStatusExplainer status={{ ...view('REJECTED', 'Rejected'), isFinal: true }} />)
    expect(screen.getByText('Rejected explained.')).toBeInTheDocument()
    expect(screen.queryByText(/Next:/)).not.toBeInTheDocument()
  })

  it('EC-NEW2 AC19 (happy): an active status names who acts next', () => {
    render(<JourneyStatusExplainer status={{ ...view('PLANNING', 'Planning'), nextActor: 'Event Coordinator' }} />)
    expect(screen.getByText(/Next: Event Coordinator\./)).toBeInTheDocument()
  })

  it('EC-NEW2 AC20 (boundary): a status code this build does not know still shows its label, in a neutral badge', () => {
    // E.g. a newer server adds a status before the frontend is redeployed.
    const unknown = { ...view('PLANNING', 'On Hold'), code: 'ON_HOLD' as StatusView['code'] }
    const { container } = render(<><JourneyStatusBadge status={unknown} /><JourneyStatusExplainer status={unknown} /></>)
    expect(screen.getAllByText('On Hold')).toHaveLength(2)
    expect(container.querySelector('.journey-badge--neutral')).not.toBeNull()
    expect(container.querySelector('[data-tone="neutral"]')).not.toBeNull()
  })

  it('EC-NEW2 AC26 (boundary): clicking the selected status again, or All, clears the filter', async () => {
    const onChange = vi.fn()
    const statuses = [view('PLANNING', 'Planning'), view('CONFIRMED', 'Confirmed')]
    const user = userEvent.setup()
    render(<StatusFilter selected="PLANNING" onChange={onChange} statuses={statuses} />)

    await user.click(screen.getByRole('button', { name: 'Planning 1' }))
    expect(onChange).toHaveBeenLastCalledWith(null)
    await user.click(screen.getByRole('button', { name: 'All 2' }))
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(screen.getByRole('button', { name: 'Planning 1' })).toHaveAttribute('aria-pressed', 'true')
  })
})

