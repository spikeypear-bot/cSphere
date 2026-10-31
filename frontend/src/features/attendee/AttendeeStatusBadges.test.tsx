// @stories AT09 AT01 AT02 AT03 AT04
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { AttendeeStatusBadges } from './AttendeeStatusBadges'
import { EVENT_BADGES, registrationBadge, type AttendeeStatusInput } from './attendeeStatus'

const now = Date.parse('2026-10-29T00:00:00Z')
const open: AttendeeStatusInput = { eventStatus: 'confirmed', registrationRequired: true,
  registrationOpensAt: '2026-10-28T00:00:00Z', registrationClosesAt: '2026-10-30T00:00:00Z',
  registrationCapacity: 10, availablePlaces: 5 }
afterEach(cleanup)

describe('AT09 AT03 AT04 attendee badge priorities', () => {
  it.each([
    ['open event', {}, 'Open for Registration', 'success'],
    ['full event', { availablePlaces: 0 }, 'Full', 'danger'],
    ['before opening', { registrationOpensAt: '2026-10-29T00:00:01Z' }, 'Not Yet Open', 'info'],
    ['at opening', { registrationOpensAt: '2026-10-29T00:00:00Z' }, 'Open for Registration', 'success'],
    ['at closing', { registrationClosesAt: '2026-10-29T00:00:00Z' }, 'Registration Closed', 'neutral'],
    ['after closing', { registrationClosesAt: '2026-10-28T23:59:59Z' }, 'Registration Closed', 'neutral'],
    ['confirmed registration', { registrationStatus: 'confirmed' }, 'Registration Confirmed', 'success'],
    ['confirmed and full', { registrationStatus: 'confirmed', availablePlaces: 0 }, 'Registration Confirmed', 'success'],
    ['confirmed and closed', { registrationStatus: 'confirmed', registrationClosesAt: '2026-10-29T00:00:00Z' }, 'Registration Confirmed', 'success'],
    ['withdrawn', { registrationStatus: 'withdrawn' }, 'Withdrawn', 'neutral'],
    ['withdrawn and cancelled', { registrationStatus: 'withdrawn', eventStatus: 'cancelled' }, 'Withdrawn', 'neutral'],
    ['withdrawn and past', { registrationStatus: 'withdrawn', eventStatus: 'completed' }, 'Withdrawn', 'neutral'],
    ['withdrawn and full', { registrationStatus: 'withdrawn', availablePlaces: 0 }, 'Withdrawn', 'neutral'],
    ['paused', { eventStatus: 'reconfirming' }, 'Registration Paused', 'warning'],
    ['confirmed during reconfirmation', { eventStatus: 'reconfirming', registrationStatus: 'confirmed' }, 'Registration Confirmed', 'success'],
    ['cancelled without registration', { eventStatus: 'cancelled' }, 'Registration Unavailable', 'neutral'],
    ['cancelled with registration', { eventStatus: 'cancelled', registrationStatus: 'event_cancelled' }, 'Cancelled', 'danger'],
    ['cancelled record', { registrationStatus: 'event_cancelled' }, 'Cancelled', 'danger'],
    ['past without registration', { eventStatus: 'completed' }, 'Registration Closed', 'neutral'],
    ['past with registration', { eventStatus: 'completed', registrationStatus: 'confirmed' }, 'Registration Confirmed', 'success'],
    ['not required', { registrationRequired: false }, 'Registration Not Required', 'neutral'],
    ['paused and not required', { eventStatus: 'reconfirming', registrationRequired: false }, 'Registration Paused', 'warning'],
    ['full before opening', { availablePlaces: 0, registrationOpensAt: '2026-10-29T00:00:01Z' }, 'Not Yet Open', 'info'],
    ['missing settings', { registrationOpensAt: null }, 'Registration Unavailable', 'neutral'],
    ['unknown remaining places', { availablePlaces: null }, 'Registration Unavailable', 'neutral'],
    ['unknown requirement', { registrationRequired: null }, 'Registration Unavailable', 'neutral'],
  ] as [string, Partial<AttendeeStatusInput>, string, string][])(
    'AT03 AT04 (boundary): %s', (_name, patch, label, tone) => {
      expect(registrationBadge({ ...open, ...patch }, now)).toEqual({ label, tone })
    },
  )

  it('AT09 AC14 (happy): a registration for a cancelled event is shown as Cancelled', () => {
    expect(registrationBadge({ ...open, eventStatus: 'cancelled', registrationStatus: 'event_cancelled' }, now))
      .toEqual({ label: 'Cancelled', tone: 'danger' })
  })

  it.each(['confirmed', 'reconfirming', 'cancelled', 'completed'] as const)(
    'AT02 AT04 (happy): %s uses two labelled badges in event-first order and the shared palette', eventStatus => {
      render(<AttendeeStatusBadges value={{ ...open, eventStatus, registrationStatus: 'confirmed' }} />)
      const group = screen.getByLabelText('Event and registration statuses')
      const badges = group.querySelectorAll('[data-attendee-badge]')
      expect(badges).toHaveLength(2)
      expect(badges[0]).toHaveAttribute('data-attendee-badge', 'event')
      expect(badges[0]).toHaveAttribute('data-tone', EVENT_BADGES[eventStatus].tone)
      expect(badges[0]).toHaveTextContent(EVENT_BADGES[eventStatus].label)
      expect(badges[1]).toHaveAttribute('data-attendee-badge', 'registration')
      expect(within(group).getByText('Event status')).toBeInTheDocument()
      expect(within(group).getByText('Registration status')).toBeInTheDocument()
    },
  )
})
