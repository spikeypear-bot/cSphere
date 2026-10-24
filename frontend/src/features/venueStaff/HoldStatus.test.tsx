// @stories EC-C3
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { HoldStatus } from './HoldStatus'
import type { VenueHold } from '../../types/venueHold'

// Expires 2027-03-01T01:00Z = 1 Mar 2027, 9:00 am SGT; the 24-hour reminder window opened a day earlier.
const hold: VenueHold = { holdId: 'h1', bookingId: 'b1', venueId: 'v1', eventId: 'e1', eventName: 'Alumni Gala',
  reservedStart: '2027-03-10T09:30:00+08:00', reservedEnd: '2027-03-10T12:45:00+08:00',
  expiresAt: '2027-03-01T01:00:00Z', reminderDueAt: '2027-02-28T01:00:00Z', serverTime: '2027-02-28T01:00:00Z',
  status: 'active', createdBy: 'vs1', createdAt: '2027-02-27T00:00:00Z', endedAt: null }

describe('EC-C3 HoldStatus on the coordinator venue booking page', () => {
  beforeEach(() => {
    // A wrong workstation clock must not matter: only server time counts.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2030-01-01T00:00:00Z'))
  })
  afterEach(() => { cleanup(); vi.useRealTimers() })

  it('EC-C3 AC8 (boundary): an active hold is marked Expiring soon when server time reaches the reminder time, with its expiry shown', () => {
    render(<HoldStatus holds={[hold]} />)
    act(() => vi.advanceTimersByTime(0))
    const item = screen.getByRole('listitem')
    expect(item).toHaveTextContent('Tentative hold: Active')
    expect(item).toHaveTextContent(/Expiry \(SGT\): 1 Mar 2027, 0?9:00\s?am/i)
    expect(screen.getByText('Expiring soon')).toBeInTheDocument()
  })

  it('EC-C3 AC8 (boundary): one second before the reminder time it is not marked, then it is', () => {
    render(<HoldStatus holds={[{ ...hold, serverTime: '2027-02-28T00:59:59Z' }]} />)
    act(() => vi.advanceTimersByTime(999))
    expect(screen.queryByText('Expiring soon')).not.toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.getByText('Expiring soon')).toBeInTheDocument()
  })

  it('EC-C3 AC8 (negative): an expired hold shows as Expired, never Expiring soon or a confirmed booking', () => {
    render(<HoldStatus holds={[{ ...hold, status: 'expired', serverTime: '2027-03-01T02:00:00Z', endedAt: '2027-03-01T01:00:00Z' }]} />)
    act(() => vi.advanceTimersByTime(0))
    const item = screen.getByRole('listitem')
    expect(item).toHaveTextContent('Tentative hold: Expired')
    expect(item).not.toHaveTextContent(/Expiring soon|Confirmed|Approved/i)
  })

  it('EC-C3 AC8 (boundary): an active hold reaching its expiry on screen turns Expired and loses the mark', () => {
    render(<HoldStatus holds={[{ ...hold, serverTime: '2027-03-01T00:59:59Z' }]} />)
    act(() => vi.advanceTimersByTime(0))
    expect(screen.getByText('Expiring soon')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1000))
    const item = screen.getByRole('listitem')
    expect(item).toHaveTextContent('Tentative hold: Expired')
    expect(item).not.toHaveTextContent(/Expiring soon|Confirmed|Approved/i)
  })

  it.each(['released', 'converted'] as const)('EC-C3 AC8 (negative): a %s hold is never marked Expiring soon', status => {
    render(<HoldStatus holds={[{ ...hold, status, endedAt: '2027-02-28T00:30:00Z' }]} />)
    act(() => vi.advanceTimersByTime(0))
    expect(screen.queryByText('Expiring soon')).not.toBeInTheDocument()
  })
})
