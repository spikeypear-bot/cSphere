// @stories AT01 AT02
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'
import { AttendeeEventsPage } from './AttendeeEventsPage'

const details = {
  eventId: 'event-1', eventName: 'Community Conference', description: 'Meet the community.',
  startDatetime: '2027-03-01T09:00:00+08:00', endDatetime: '2027-03-01T17:00:00+08:00',
  status: 'confirmed', updatedAt: '2026-12-02T12:00:00Z',
  accessibilityInformation: ['step_free_access'], registrationRequired: true,
  registrationOpensAt: null, registrationClosesAt: null, registrationCapacity: null,
  attendeeInstructions: null,
  availablePlaces: 8,
  registrationReference: null, registeredAt: null,
  venues: [{ venueId: 'venue-1', name: 'Bay Hall', address: '10 Bay Road', unavailable: false }],
}

function renderPage() {
  return render(<MemoryRouter initialEntries={['/attendee/events/event-1']}>
    <Routes><Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} /></Routes>
  </MemoryRouter>)
}

function renderBrowsePage() {
  return render(<MemoryRouter initialEntries={['/attendee/browse']}>
    <Routes><Route path="/attendee/browse" element={<AttendeeEventsPage />} /></Routes>
  </MemoryRouter>)
}

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('AT02 attendee event details', () => {
  it('AT02 AC1 (happy): browse lists confirmed events and opens the attendee details route', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200,
      text: async () => JSON.stringify([{ eventId: 'event-1', eventName: 'Community Conference',
        startDatetime: '2027-03-01T09:00:00+08:00', endDatetime: '2027-03-01T17:00:00+08:00',
        status: 'confirmed', registrationRequired: true }]) }))
    renderBrowsePage()
    const link = await screen.findByRole('link', { name: 'Community Conference' })
    expect(link).toHaveAttribute('href', '/attendee/events/event-1')
  })

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

  it('AT01 AC5 (happy): submits a registration and displays its unique confirmation reference', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify(details) })
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({
        reference: 'CS-123456789ABC', registeredAt: '2027-01-01T12:00:00Z',
      }) }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText(/CS-123456789ABC/)).toBeInTheDocument()
  })

  it('AT01 AC10 (failure): explains a failed save and offers a safe retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify(details) })
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({
        reference: 'CS-RETRY', registeredAt: '2027-01-01T12:00:00Z',
      }) })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    const button = await screen.findByRole('button', { name: 'Register for this event' })
    fireEvent.click(button)
    expect(await screen.findByText(/registration could not be saved/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText(/CS-RETRY/)).toBeInTheDocument()
  })
})
