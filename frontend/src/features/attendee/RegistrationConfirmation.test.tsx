// @stories AT09 AT03 AT01 AT02 AT04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RegistrationConfirmation, SavedRegistrationConfirmation } from './RegistrationConfirmation'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'
import type { RegistrationDetails } from './registrationView'

const record: RegistrationDetails = {
  registrationId: 'reg-1', reference: 'CS-AT03', registeredAt: '2026-12-01T00:00:00Z',
  registrationStatus: 'confirmed', eventId: 'event-1', eventName: 'Community conference',
  startDatetime: '2027-03-01T09:00:00+08:00', endDatetime: '2027-03-01T17:00:00+08:00',
  eventStatus: 'confirmed', eventDetailsAvailable: true, withdrawalAvailable: true,
  withdrawalReason: 'Withdrawal is available before the event starts.',
}
const venues = [
  { venueId: 'v1', name: 'Main Hall', address: '10 Bay Road', unavailable: false },
  { venueId: 'v2', name: 'Breakout Room', address: '12 Bay Road', unavailable: true },
]
const event = { eventId: 'event-1', eventName: record.eventName, startDatetime: record.startDatetime,
  endDatetime: record.endDatetime, status: 'confirmed', accessibilityInformation: [], venues,
  registrationRequired: true, availablePlaces: 5, registrationCapacity: 10, registrationOpensAt: '2020-01-01T00:00:00Z',
  registrationClosesAt: '2027-03-01T01:00:00Z', updatedAt: null, attendeeInstructions: null,
  registrationReference: null, registeredAt: null, registrationId: null }
const response = (data: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(data) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('AT09 AT03 registration confirmation', () => {
  it('AT03 (happy): shows the saved record, dates, separate statuses and all approved venues', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ venues })))
    render(<MemoryRouter><RegistrationConfirmation registration={record} /></MemoryRouter>)
    expect(await screen.findByText('Main Hall')).toBeInTheDocument()
    expect(screen.getByText('Breakout Room')).toBeInTheDocument()
    expect(screen.getByText('10 Bay Road')).toBeInTheDocument()
    expect(screen.getByText('12 Bay Road')).toBeInTheDocument()
    expect(screen.getByText(/venue is unavailable while arrangements/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: record.eventName })).toBeInTheDocument()
    expect(screen.getByText('CS-AT03')).toBeInTheDocument()
    expect(screen.getByText('1 December 2026 at 8:00 am (SGT)')).toBeInTheDocument()
    expect(screen.getByText('1 March 2027 at 9:00 am (SGT)')).toBeInTheDocument()
  })

  it('AT03 (failure): saved registration survives confirmation load failure and retry uses GET only', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(response(record)).mockResolvedValue(response({ venues }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter><SavedRegistrationConfirmation registrationId="reg-1" reference="CS-AT03" /></MemoryRouter>)
    expect(await screen.findByText(/Registration saved.*Confirmation details could not be loaded/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry confirmation details' }))
    expect(await screen.findByText('Main Hall')).toBeInTheDocument()
    expect(fetchMock.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true)
    expect(screen.getByRole('link', { name: 'View registration confirmation' })).toHaveAttribute('href', '/attendee/my-registrations/reg-1')
  })

  it('AT03 (failure): approved-venue failure never offers another registration submission', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(response({ venues })))
    render(<MemoryRouter><RegistrationConfirmation registration={record} /></MemoryRouter>)
    expect(await screen.findByRole('alert')).toHaveTextContent(/registration is saved/)
    expect(screen.getByText('CS-AT03')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry venue details' }))
    expect(await screen.findByText('Main Hall')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })

  it.each(['withdrawn', 'event_cancelled'] as const)('AT09 AC14, AT03 (cross-cutting): old %s confirmations show history, not active success', async registrationStatus => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ venues })))
    render(<MemoryRouter><RegistrationConfirmation registration={{ ...record, registrationStatus, withdrawalAvailable: false }} /></MemoryRouter>)
    expect(screen.getByText(registrationStatus === 'withdrawn' ? 'Withdrawn' : 'Cancelled')).toBeInTheDocument()
    expect(screen.queryByText(/Registration Confirmed/)).not.toBeInTheDocument()
    expect(screen.getByText('CS-AT03')).toBeInTheDocument()
    expect(await screen.findByText('Main Hall')).toBeInTheDocument()
  })

  it('AT03 (cross-cutting): reconfirmation preserves the confirmed registration while a later withdrawal refreshes the old link', async () => {
    let withdrawn = false
    vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.includes('/registrations/')
      ? { ...record, eventStatus: 'reconfirming', registrationStatus: withdrawn ? 'withdrawn' : 'confirmed' } : { venues })))
    render(<MemoryRouter><SavedRegistrationConfirmation registrationId="reg-1" reference="CS-AT03" /></MemoryRouter>)
    expect(await screen.findByText('Awaiting Re-confirmation')).toBeInTheDocument()
    expect(within(screen.getByText('Registration status').parentElement!).getByText('Registration Confirmed')).toBeInTheDocument()
    withdrawn = true; fireEvent(window, new Event('focus'))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    expect(screen.queryByText(/Registration Confirmed/)).not.toBeInTheDocument()
  })

  it('AT03 AT01 (negative): a rejected registration shows its error and no confirmation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(event))
      .mockResolvedValue(response({ message: 'This event is full.' }, 409)))
    render(<MemoryRouter initialEntries={['/attendee/events/event-1']}><Routes>
      <Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} />
    </Routes></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/This event is full/)
    expect(screen.queryByRole('region', { name: 'Registration confirmation' })).not.toBeInTheDocument()
  })

  it('AT03 AT01 (happy): success loads its owned confirmation and refreshes authoritative capacity', async () => {
    let saved = false
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
      if (options.method === 'POST') { saved = true; return response({ ...record, status: 'confirmed' }) }
      if (url.includes('/registrations/')) return response(record)
      return response({ ...event, availablePlaces: saved ? 4 : 5,
        registrationReference: saved ? record.reference : null,
        registeredAt: saved ? record.registeredAt : null,
        registrationId: saved ? record.registrationId : null,
        registrationStatus: saved ? 'confirmed' : null })
    }))
    render(<MemoryRouter initialEntries={['/attendee/events/event-1']}><Routes>
      <Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} />
    </Routes></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('CS-AT03')).toBeInTheDocument()
    const confirmation = screen.getByRole('region', { name: 'Registration confirmation' })
    expect(await within(confirmation).findByText('Breakout Room')).toBeInTheDocument()
    expect(screen.getByText('4 places available')).toBeInTheDocument()
  })
})
