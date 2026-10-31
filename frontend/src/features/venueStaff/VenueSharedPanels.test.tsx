// @stories DEV16 VS02 VS08 VS18 VS-C4
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BookingConflictList } from './BookingConflictList'
import { BookingConflictCard } from './BookingConflictCard'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { EventRequirementsPanel } from './EventRequirementsPanel'
import type { ScheduleBooking } from './VenueUnavailabilityPanel'
import type { VenueDto, VenueLayout } from '../../types/venue'
import type { VenueBookingDto } from '../../types/venueBooking'
import { dayTime } from './venueCalendar'
import { unavailabilityDateErrors } from './unavailabilityValidation'

const venue: VenueDto = { venueId: 'room', venueAddress: 'Hall address', venueCapacity: null,
  supportedLayouts: ['legacy_layout' as VenueLayout], venueAccessibilities: [], venueFacilities: [],
  operatingInformation: '', additionalInformation: '   ' }
const event: VenueBookingDto['event'] = { eventId: 'event', eventName: 'Workshop',
  startDatetime: '2027-04-01T10:00:00+08:00', endDatetime: '2027-04-01T12:00:00+08:00',
  expectedAttendance: 100, venueRequirements: '', accessibilityNeeds: [], equipmentRequirements: null }
const first: ScheduleBooking = { bookingId: 'first', venueId: 'room', eventName: 'First event', status: 'approved',
  startDateTime: '2027-04-01T10:00:00+08:00', endDateTime: '2027-04-01T12:00:00+08:00',
  effectiveStart: '2027-04-01T09:30:00+08:00', effectiveEnd: '2027-04-01T12:45:00+08:00' }
afterEach(cleanup)

describe('DEV16 shared Venue Staff display boundaries', () => {
  it('DEV16 (boundary): read-only legacy venue details retain a useful identity without action controls', () => {
    render(<MemoryRouter><VenueDetailsPanel venue={venue} readOnly title={venue.venueAddress} /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: 'Hall address' })).toBeInTheDocument()
    expect(screen.getByText('Capacity not recorded')).toBeInTheDocument()
    expect(screen.getByText('legacy_layout')).toBeInTheDocument()
    expect(screen.getByText('No additional information recorded.')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Venue ID')).not.toBeInTheDocument()
  })
  it('DEV16 (boundary): empty conflict pairs hide the conflict region', () => {
    render(<BookingConflictList pairs={[]} />)
    expect(screen.queryByRole('region')).not.toBeInTheDocument()
  })
  it.each([false, true])('DEV16 (boundary): conflict intersection and legacy venue/time fallbacks remain readable (%s)', reverse => {
    const second = { ...first, bookingId: 'second', eventName: 'Second event', status: 'legacy',
      effectiveStart: '2027-04-01T11:00:00+08:00', effectiveEnd: '2027-04-01T13:00:00+08:00' }
    render(<MemoryRouter><BookingConflictList pairs={[reverse ? { first: second, second: first } : { first, second }]} /></MemoryRouter>)
    const list = screen.getByRole('region', { name: 'Booking Conflicts' })
    expect(within(list).getAllByText('room')).toHaveLength(2)
    expect(within(list).getByText('Unknown status')).toBeInTheDocument()
    expect(within(list).getAllByText('30 / 45 minutes')).toHaveLength(1)
    expect(within(list).getByText(/Conflict period/).parentElement).toHaveTextContent('11:00 am')
    expect(within(list).getByText(/Conflict period/).parentElement).toHaveTextContent('12:45 pm')
  })
  it('DEV16 (boundary): a supplied venue name is used when legacy bookings have no venue name', () => {
    render(<MemoryRouter><BookingConflictList venueName="Shared hall" showHeading={false}
      pairs={[{ first, second: { ...first, bookingId: 'second' } }]} /></MemoryRouter>)
    expect(screen.getAllByText('Shared hall')).toHaveLength(2)
    expect(screen.queryByRole('heading', { name: 'Booking Conflicts' })).not.toBeInTheDocument()
  })
  it('DEV16 (boundary): zero setup is explicit and a non-overlapping occupied period is not highlighted', () => {
    render(<BookingConflictCard request={event} conflict={{ bookingId: 'other', eventName: 'Earlier event',
      startDatetime: '2027-04-01T08:00:00+08:00', endDatetime: '2027-04-01T09:00:00+08:00',
      setupDurationMinutes: 0, turnaroundDurationMinutes: 0,
      effectiveStart: '2027-04-01T08:00:00+08:00', effectiveEnd: '2027-04-01T09:00:00+08:00' }} />)
    expect(screen.getByText('0 minutes (no setup period)')).toBeInTheDocument()
    const occupied = screen.getByText('Venue Occupied').nextElementSibling!
    expect(occupied).not.toHaveClass('booking-time-conflict')
  })
  it('DEV16 (boundary): unknown legacy accessibility and facility labels remain visible in room requirements', () => {
    render(<EventRequirementsPanel event={event} status="pending" requirements={{
      expectedAttendance: 50, venueRequirements: '', accessibilityNeeds: ['legacy_access'], requiredFacilities: ['legacy_facility'],
    }} />)
    expect(screen.getByText('legacy_access')).toBeInTheDocument()
    expect(screen.getByText('legacy_facility')).toBeInTheDocument()
    expect(screen.getByText('50 people')).toBeInTheDocument()
  })
  it('DEV16 (boundary): an occupancy ending exactly at Singapore midnight displays 24:00', () => {
    expect(dayTime({ id: 'b', entryId: 'b:event', type: 'approved', phase: 'Event', title: 'Workshop', start: '2027-04-01T10:00:00+08:00',
      end: '2027-04-02T00:00:00+08:00' }, '2027-04-01')).toBe('10:00 – 24:00')
    expect(dayTime({ id: 'b', entryId: 'b:event', type: 'approved', phase: 'Event', title: 'Workshop',
      start: '2027-03-31T23:00:00+08:00', end: '2027-04-02T01:00:00+08:00' }, '2027-04-01')).toBe('← – →')
  })
  it('DEV16 (boundary): Gregorian leap-century dates validate on both sides of the 400-year rule', () => {
    const now = new Date('2000-01-01T00:00:00Z')
    expect(unavailabilityDateErrors('2100-02-29T10:00', '2100-03-01T10:00', now).start).toBe('Enter a valid start calendar date and time.')
    expect(unavailabilityDateErrors('2400-02-29T10:00', '2400-02-29T11:00', now)).toEqual({})
    expect(unavailabilityDateErrors('not-a-date', '2027-04-01T11:00', now).start).toBe('Enter a valid start calendar date and time.')
  })
  it('DEV16 (boundary): normalised millisecond input stays valid without accepting impossible seconds', () => {
    const now = new Date('2027-04-01T00:00:00Z')
    expect(unavailabilityDateErrors('2027-04-01T10:00:30.123', '2027-04-01T11:00:45.987', now)).toEqual({})
    expect(unavailabilityDateErrors('2027-04-01T10:00:60.000', '2027-04-01T11:00:45.987', now).start).toBe('Enter a valid start calendar date and time.')
  })
})
