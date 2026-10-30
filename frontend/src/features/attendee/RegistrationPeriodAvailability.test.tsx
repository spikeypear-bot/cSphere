// @stories EO12
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'

function details(opensAt: string, closesAt: string) {
  return {
    eventId: 'event-1',
    eventName: 'Community Fair',
    description: null,
    startDatetime: new Date(Date.now() + 2 * 24 * 60 * 60_000).toISOString(),
    endDatetime: new Date(Date.now() + 2.5 * 24 * 60 * 60_000).toISOString(),
    status: 'confirmed',
    updatedAt: null,
    accessibilityInformation: [],
    registrationRequired: true,
    registrationOpensAt: opensAt,
    registrationClosesAt: closesAt,
    registrationCapacity: 10,
    availablePlaces: 5,
    attendeeInstructions: null,
    registrationReference: null,
    registeredAt: null,
    registrationId: null,
    registrationStatus: null,
    venues: [],
  }
}

function renderDetails(value: ReturnType<typeof details>) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    text: async () => JSON.stringify(value),
  }))
  return render(<MemoryRouter initialEntries={['/attendee/events/event-1']}>
    <Routes><Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} /></Routes>
  </MemoryRouter>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('EO12 attendee registration availability', () => {
  it('EO12 AC7, AC8 (boundary): before opening, names the SGT opening time and offers no registration action', async () => {
    const opensAt = new Date(Date.now() + 60 * 60_000).toISOString()
    const closesAt = new Date(Date.now() + 90 * 60_000).toISOString()
    renderDetails(details(opensAt, closesAt))

    expect(await screen.findByText(/Registration has not opened yet and opens/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })

  it('EO12 AC7, AC9 (boundary): at and after closing, reports closed and offers no registration action', async () => {
    const opensAt = new Date(Date.now() - 2 * 60 * 60_000).toISOString()
    const closesAt = new Date(Date.now() - 60 * 60_000).toISOString()
    renderDetails(details(opensAt, closesAt))

    expect(await screen.findByText('Registration has closed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })
})
