import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { apiClient } from '../../lib/apiClient'
import { VenueSchedulePage } from './VenueSchedulePage'
import { calendarDays, calendarEntries, entriesOnDay, shiftMonth, singaporeDate } from './venueCalendar'
import type { VenueSchedule } from './VenueUnavailabilityPanel'
vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(), apiClient: { get: vi.fn() } }))
const month = singaporeDate().slice(0, 7)
const at = (time: string) => `${month}-15T${time}:00+08:00`
const booking = { bookingId: 'b1', eventName: 'Workshop', status: 'approved', startDateTime: at('10:00'), endDateTime: at('12:00'), effectiveStart: at('09:30'), effectiveEnd: at('12:45') }
const schedule: VenueSchedule = { settings: { setupMinutes: 30, turnaroundMinutes: 45 }, bookings: [booking, { ...booking, bookingId: 'b2', status: 'pending', eventName: 'Pending workshop' }], unavailablePeriods: [{ unavailabilityId: 'b1', startDateTime: at('10:00'), endDateTime: at('11:00'), reason: 'Maintenance' }] }
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  vi.mocked(apiClient.get).mockImplementation(async path => path === '/venues' ? [{ venueId: 'v1', venueAddress: 'Main Hall' }, { venueId: 'v2', venueAddress: 'Other Hall' }] : schedule)
})
afterEach(() => { cleanup(); vi.resetAllMocks() })
async function mount() {
  render(<VenueSchedulePage />)
  fireEvent.change(await screen.findByLabelText('Venue'), { target: { value: 'v1' } })
  await screen.findByRole('table')
}
it('shows overlapping bookings, holds and separate buffers, with read-only details', async () => {
  await mount()
  const table = screen.getByRole('table')
  expect(within(table).getAllByRole('button')).toHaveLength(7)
  fireEvent.click(within(table).getByRole('button', { name: /Confirmed booking · Setup/ }))
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('Main Hall')).toBeInTheDocument()
  expect(within(dialog).getByText('Event timing (SGT)')).toBeInTheDocument()
  expect(within(dialog).getAllByRole('button')).toHaveLength(1)
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
  fireEvent.click(within(table).getByRole('button', { name: /Unavailable.*Maintenance/ }))
  expect(within(screen.getByRole('dialog')).getByText('Maintenance')).toBeInTheDocument()
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
  render(<VenueSchedulePage />)
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
