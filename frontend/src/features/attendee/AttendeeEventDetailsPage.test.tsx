// @stories AT02
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'

const details = {
  eventId: 'event-1', eventName: 'Community Conference', description: 'Meet the community.',
  startDatetime: '2027-03-01T09:00:00+08:00', endDatetime: '2027-03-01T17:00:00+08:00',
  status: 'confirmed', updatedAt: '2026-12-02T12:00:00Z',
  accessibilityInformation: ['step_free_access'], registrationRequired: true,
  registrationOpensAt: null, registrationClosesAt: null, registrationCapacity: null,
  attendeeInstructions: null,
  venues: [{ venueId: 'venue-1', address: '10 Bay Road', unavailable: false }],
}

function renderPage() {
  return render(<MemoryRouter initialEntries={['/attendee/events/event-1']}>
    <Routes><Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} /></Routes>
  </MemoryRouter>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('AT02 attendee event details', () => {
  it('AT02 AC2 (happy): shows approved event, time zone, venue, accessibility and registration requirement', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => JSON.stringify(details) }))
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Community Conference' })).toBeInTheDocument()
    expect(screen.getByText('Meet the community.')).toBeInTheDocument()
    expect(screen.getAllByText(/UTC\+08:00/)).toHaveLength(2)
    expect(screen.getByText('10 Bay Road')).toBeInTheDocument()
    expect(screen.getByText('Step-free access')).toBeInTheDocument()
    expect(screen.getByText('Registration is required.')).toBeInTheDocument()
  })

  it('AT02 AC9 (failure): provides retry after a load failure', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify(details) })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Community Conference' })).toBeInTheDocument()
  })

  it('AT02 AC8 (negative): explains when attendee details are unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, text: async () => JSON.stringify({ message: 'Unavailable' }) }))
    renderPage()
    expect(await screen.findByText(/event is unavailable/i)).toBeInTheDocument()
  })

  it('AT02 AC6 (boundary): keeps prior venue details visible and marks an affected venue during re-confirmation', async () => {
    const reconfirming = { ...details, status: 'reconfirming' as const,
      venues: [{ ...details.venues[0], unavailable: true }] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => JSON.stringify(reconfirming) }))
    renderPage()
    expect(await screen.findAllByText(/being re-confirmed/i)).toHaveLength(2)
    expect(screen.getByText('10 Bay Road')).toBeInTheDocument()
    expect(screen.getByText(/venue is currently unavailable/i)).toBeInTheDocument()
  })
})
