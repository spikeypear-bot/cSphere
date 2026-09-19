import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { StatusTimeline } from './StatusTimeline'

describe('StatusTimeline — EO15 visual status stepper', () => {
  afterEach(() => cleanup())

  it('shows Draft as the current step for a draft request', () => {
    render(<StatusTimeline status="draft" />)
    expect(screen.getByText('Draft').closest('li')).toHaveAttribute('data-state', 'current')
    expect(screen.getByText('Submitted').closest('li')).toHaveAttribute('data-state', 'upcoming')
  })

  it('shows Submitted as current and an "Under review" upcoming step for pending', () => {
    render(<StatusTimeline status="pending" />)
    expect(screen.getByText('Draft').closest('li')).toHaveAttribute('data-state', 'done')
    expect(screen.getByText('Submitted').closest('li')).toHaveAttribute('data-state', 'current')
    expect(screen.getByText('Under review').closest('li')).toHaveAttribute('data-state', 'upcoming')
  })

  it('shows a green Approved terminal step', () => {
    render(<StatusTimeline status="approved" />)
    expect(screen.getByText('Approved').closest('li')).toHaveAttribute('data-state', 'done')
  })

  it('shows Rejected as a distinct terminal state, not just "further along"', () => {
    render(<StatusTimeline status="rejected" />)
    expect(screen.getByText('Rejected').closest('li')).toHaveAttribute('data-state', 'rejected')
  })

  it('shows Cancelled as a distinct terminal state', () => {
    render(<StatusTimeline status="cancelled" />)
    expect(screen.getByText('Cancelled').closest('li')).toHaveAttribute('data-state', 'rejected')
  })
})
