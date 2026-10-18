// @stories VS15 VS-C2 VS-C3 VS-C4  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { apiClient } from '../../lib/apiClient'
import { VenueSchedulePage } from './VenueSchedulePage'
import { calendarDays, calendarEntries, entriesOnDay, shiftMonth, singaporeDate } from './venueCalendar'
import type { VenueSchedule } from './VenueUnavailabilityPanel'
import { MemoryRouter } from 'react-router-dom'
vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(), apiClient: { get: vi.fn() } }))
const month = singaporeDate().slice(0, 7)
const at = (time: string) => `${month}-15T${time}:00+08:00`
const booking = { bookingId: 'b1', eventName: 'Workshop', status: 'approved', startDateTime: at('10:00'), endDateTime: at('12:00'), effectiveStart: at('09:30'), effectiveEnd: at('12:45') }
const schedule: VenueSchedule = { settings: { setupMinutes: 30, turnaroundMinutes: 45 }, bookings: [booking, { ...booking, bookingId: 'b2', status: 'pending', eventName: 'Pending workshop' }], unavailablePeriods: [{ unavailabilityId: 'b1', startDateTime: at('10:00'), endDateTime: at('11:00'), reason: 'Maintenance' }] }
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues' ? [{ venueId: 'v1', venueAddress: 'Main Hall' }, { venueId: 'v2', venueAddress: 'Other Hall' }] : schedule)
})
afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks() })
async function mount() {
  render(<MemoryRouter><VenueSchedulePage /></MemoryRouter>)
  fireEvent.change(await screen.findByLabelText('Venue'), { target: { value: 'v1' } })
  await screen.findByRole('table')
}
async function mountWithClock() {
  vi.useFakeTimers()
  await act(async () => { render(<MemoryRouter><VenueSchedulePage /></MemoryRouter>) })
  await act(async () => { fireEvent.change(screen.getByLabelText('Venue'), { target: { value: 'v1' } }) })
  expect(screen.getByRole('table')).toBeInTheDocument()
}
it('VS-C3 VS-C4 (regression): hold expiry removes occupancy and its open dialog while buffer conflict indicators remain', async () => {
  const partner = { ...booking, bookingId: 'b3', eventName: 'Next event', startDateTime: at('13:00'),
    endDateTime: at('14:00'), effectiveStart: at('12:30'), effectiveEnd: at('14:00') }
  const serverTime = new Date().toISOString()
  const hold = { holdId: 'h1', bookingId: 'b2', venueId: 'v1', eventId: 'e2', eventName: 'Held workshop',
    reservedStart: at('15:00'), reservedEnd: at('16:00'), status: 'active' as const,
    expiresAt: new Date(Date.parse(serverTime) + 1000).toISOString(), serverTime,
    createdBy: 'vs1', createdAt: serverTime, endedAt: null }
  const integrated = { ...schedule, bookings: [...schedule.bookings, partner], holds: [hold] }
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues'
    ? [{ venueId: 'v1', venueAddress: 'Main Hall' }] : integrated)
  await mountWithClock()
  const held = screen.getByRole('button', { name: /Tentative Hold.*Held workshop/i })
  expect(held).toHaveAttribute('data-booking-status', 'tentative')
  expect(held).toHaveAttribute('data-conflict', 'false')
  fireEvent.click(held)
  expect(within(screen.getByRole('dialog')).getByText('Hold expiry (SGT)')).toBeInTheDocument()
  expect(within(screen.getByRole('dialog')).getByText('Active')).toBeInTheDocument()
  expect(within(screen.getByRole('dialog')).getByRole('link', { name: 'View booking details' })).toHaveAttribute('href', '/venue-staff/bookings/b2')
  // Even a stale response must not restore a hold after its local deadline.
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  expect(screen.queryByRole('button', { name: /Tentative Hold.*Held workshop/i })).not.toBeInTheDocument()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('table').querySelectorAll('[data-conflict="true"]')).toHaveLength(5)
  expect(screen.getByRole('link', { name: 'Next event' })).toHaveAttribute('href', '/venue-staff/bookings/b3')
})
it('VS-C3 VS-C4 (regression): polling refreshes booking statuses and conflict indicators together', async () => {
  await mountWithClock()
  vi.mocked(apiClient.get).mockClear()
  vi.mocked(apiClient.get).mockResolvedValue({ ...schedule, bookings: schedule.bookings.map(b => ({ ...b, status: 'approved' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
  expect(apiClient.get).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('button', { name: /Pending request/ })).not.toBeInTheDocument()
  expect(within(screen.getByRole('table')).getAllByRole('button', { name: /Approved booking/ })).toHaveLength(6)
  expect(screen.getByRole('table').querySelectorAll('[data-conflict="true"]')).toHaveLength(6)
})
it('refreshes on window focus and removes released occupancy while retaining other entries', async () => {
  await mount()
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues'
    ? [{ venueId: 'v1', venueAddress: 'Main Hall' }]
    : { ...schedule, bookings: schedule.bookings.filter(b => b.bookingId !== 'b1') })
  fireEvent(window, new Event('focus'))
  const table = await screen.findByRole('table')
  expect(within(table).queryByRole('button', { name: /Approved booking/ })).not.toBeInTheDocument()
  expect(within(table).getAllByRole('button', { name: /Pending request/ })).toHaveLength(3)
  expect(within(table).getByRole('button', { name: /Unavailable.*Maintenance/ })).toBeInTheDocument()
})
it('VS-C4 AC7, AC8, AC9, AC10 (conflict): highlights buffers, links both bookings and removes resolved indicators on refresh', async () => {
  const second = { ...booking, bookingId: 'b3', eventName: 'Next event', startDateTime: at('13:00'), endDateTime: at('14:00'), effectiveStart: at('12:30'), effectiveEnd: at('14:00') }
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues'
    ? [{ venueId: 'v1', venueAddress: 'Main Hall' }] : { ...schedule, bookings: [...schedule.bookings, second] })
  await mount()
  const list = screen.getByRole('region', { name: 'Booking Conflicts' })
  expect(within(list).getByRole('link', { name: 'Next event' })).toHaveAttribute('href', '/venue-staff/bookings/b3')
  expect(within(list).getByText('30 / 45 minutes')).toBeInTheDocument()
  expect(within(list).getByText('Conflict period (SGT):')).toBeInTheDocument()
  const table = screen.getByRole('table')
  const entries = within(table).getAllByRole('button', { name: /Approved booking/ })
  expect(entries).toHaveLength(5)
  entries.forEach(entry => expect(entry).toHaveAttribute('data-conflict', 'true'))
  entries.forEach(entry => expect(entry).toHaveAttribute('data-booking-status', 'approved'))
  within(table).getAllByRole('button', { name: /Tentative hold|Unavailable/ }).forEach(entry => expect(entry).toHaveAttribute('data-conflict', 'false'))
  fireEvent.click(entries[0])
  expect(within(screen.getByRole('dialog')).getByRole('link', { name: 'Next event' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Close' }))
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues' ? [{ venueId: 'v1', venueAddress: 'Main Hall' }] : schedule)
  fireEvent(window, new Event('focus'))
  await screen.findByText('No booking conflicts detected for this venue in this view.')
  expect(screen.getByRole('table').querySelector('[data-conflict="true"]')).toBeNull()
})
it('shows overlapping bookings, holds and separate buffers, with read-only details', async () => {
  await mount()
  const table = screen.getByRole('table')
  expect(within(table).getAllByRole('button')).toHaveLength(7)
  fireEvent.click(within(table).getByRole('button', { name: /Approved booking · Setup/ }))
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('Main Hall')).toBeInTheDocument()
  expect(within(dialog).getByText('Event timing (SGT)')).toBeInTheDocument()
  expect(within(dialog).getAllByRole('button')).toHaveLength(1)
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
  fireEvent.click(within(table).getByRole('button', { name: /Unavailable.*Maintenance/ }))
  expect(within(screen.getByRole('dialog')).getByText('Maintenance')).toBeInTheDocument()
})
it('VS-C4 AC8 (boundary): highlights a visible booking with an off-screen conflict partner', async () => {
  const partner = { ...booking, bookingId: 'outside', eventName: 'Off-screen event', startDateTime: at('12:30'), effectiveStart: at('12:30'), endDateTime: at('13:30'), effectiveEnd: at('13:30') }
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues'
    ? [{ venueId: 'v1', venueAddress: 'Main Hall' }]
    : { ...schedule, activeConflicts: [{ first: booking, second: partner }] })
  await mount()
  expect(screen.getByRole('link', { name: 'Off-screen event' })).toHaveAttribute('href', '/venue-staff/bookings/outside')
  const table = screen.getByRole('table')
  expect(within(table).queryByRole('button', { name: /Off-screen event/ })).not.toBeInTheDocument()
  within(table).getAllByRole('button', { name: /Approved booking/ }).forEach(entry => expect(entry).toHaveAttribute('data-conflict', 'true'))
})
it('requests displayed dates, navigates periods and returns to today', async () => {
  await mount()
  const path = vi.mocked(apiClient.get).mock.calls.at(-1)![0]
  expect(new URLSearchParams(path.split('?')[1]).get('start')).toBe(`${calendarDays(month)[0]}T00:00:00+08:00`)
  fireEvent.click(screen.getByRole('button', { name: 'Next month' }))
  await screen.findByRole('table')
  expect(screen.getByText(/No schedule entries for this month/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))
  await screen.findByRole('table')
  expect(screen.queryByText(/No schedule entries for this month/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Next month' }))
  await screen.findByRole('table')
  fireEvent.click(screen.getByRole('button', { name: 'Today' }))
  await screen.findByRole('table')
  expect(screen.queryByText(/No schedule entries for this month/)).not.toBeInTheDocument()
})
it('clears previous entries on refresh failure and supports retry', async () => {
  await mount()
  vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Offline'))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh schedule' }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByText('Workshop')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry schedule' }))
  await screen.findByRole('table')
})
it('ignores a late response for the previous venue', async () => {
  let resolve!: (value: VenueSchedule) => void
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues' ? [{ venueId: 'v1', venueAddress: 'Main Hall' }, { venueId: 'v2', venueAddress: 'Other Hall' }] : path.includes('/v1/') ? new Promise<VenueSchedule>(r => { resolve = r }) : { ...schedule, bookings: [], unavailablePeriods: [] })
  render(<MemoryRouter><VenueSchedulePage /></MemoryRouter>)
  fireEvent.change(await screen.findByLabelText('Venue'), { target: { value: 'v1' } })
  fireEvent.change(screen.getByLabelText('Venue'), { target: { value: 'v2' } })
  await screen.findByText('No schedule entries for this month.')
  await act(async () => { resolve(schedule) })
  expect(screen.queryByText('Workshop')).not.toBeInTheDocument()
})
it('normalises distinct IDs, omits zero buffers and assigns multi-day entries with exclusive midnight ends', () => {
  const entries = calendarEntries(schedule)
  expect(new Set(entries.map(e => e.id)).size).toBe(7)
  const zero = calendarEntries({ ...schedule, unavailablePeriods: [], bookings: [{ ...booking, effectiveStart: booking.startDateTime, effectiveEnd: booking.endDateTime }] })
  expect(zero.map(e => e.phase)).toEqual(['Event'])
  const spanning = [{ ...entries[0], start: '2027-03-31T15:00:00Z', end: '2027-04-01T16:00:00Z' }]
  expect(entriesOnDay(spanning, '2027-03-31')).toHaveLength(1)
  expect(entriesOnDay(spanning, '2027-04-01')).toHaveLength(1)
  expect(entriesOnDay(spanning, '2027-04-02')).toHaveLength(0)
  expect(calendarDays('2028-02')).toContain('2028-02-29')
  expect(shiftMonth('2027-12', 1)).toBe('2028-01')
  expect(singaporeDate(new Date('2027-04-01T16:00:00Z'))).toBe('2027-04-02')
})

it('preserves the calendar height during navigation, venue changes and refresh without retaining stale entries', async () => {
  await mount()
  const frame = screen.getByRole('region', { name: 'Venue schedule' }).parentElement!
  vi.spyOn(frame, 'getBoundingClientRect').mockReturnValue({ height: 1200 } as DOMRect)
  const actions = [
    () => fireEvent.click(screen.getByRole('button', { name: 'Next month' })),
    () => fireEvent.click(screen.getByRole('button', { name: 'Previous month' })),
    () => fireEvent.click(screen.getByRole('button', { name: 'Previous month' })),
    () => fireEvent.click(screen.getByRole('button', { name: 'Today' })),
    () => fireEvent.click(screen.getByRole('button', { name: 'Refresh schedule' })),
    () => fireEvent.change(screen.getByLabelText('Venue'), { target: { value: 'v2' } }),
  ]
  for (const action of actions) {
    let resolve!: (value: VenueSchedule) => void
    vi.mocked(apiClient.get).mockImplementationOnce(() => new Promise<VenueSchedule>(r => { resolve = r }))
    action()
    expect(frame.style.minHeight).toBe('1200px')
    expect(screen.getByText('Loading schedule…')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh schedule' })).toBeDisabled()
    await act(async () => { resolve(schedule) })
    expect(frame.style.minHeight).toBe('1200px')
    expect(screen.getByRole('button', { name: 'Refresh schedule' })).toBeEnabled()
  }
  vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Offline'))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh schedule' }))
  await screen.findByRole('alert')
  expect(frame.style.minHeight).toBe('1200px')
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  vi.restoreAllMocks()
})
