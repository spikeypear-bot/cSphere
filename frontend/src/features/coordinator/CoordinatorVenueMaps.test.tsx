// @stories DEV16 EC03 EC04 EC-C2 EC10 AU04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { EventDto } from '../../types/event'
import type { VenueOptionDto } from '../../types/venueBookingRequest'
import type { VenueMapProps } from '../venueMap/VenueMap'
import { hasCoordinates } from '../venueMap/mapData'
import { VenueBookingRequestPage } from './VenueBookingRequestPage'
import { VenueSearchPage } from './VenueSearchPage'

// The page tests exercise the real filters/selection/request code; renderer and
// real Leaflet marker grouping are covered separately by map unit/browser tests.
vi.mock('../venueMap/VenueMap', () => ({ default: ({ venues, selectedId, selectedIds, onSelect, onToggleVenue }: VenueMapProps) =>
  <section role="region" aria-label="Interactive venue map" data-focus={selectedId}>
    {venues.filter(hasCoordinates).map(venue => <div key={venue.venueId}>
      <button onClick={() => onSelect(venue.venueId)}>Location {venue.venueId}</button>
      {onToggleVenue && <button role="checkbox" aria-checked={selectedIds?.includes(venue.venueId)}
        onClick={() => { onSelect(venue.venueId); onToggleVenue(venue.venueId) }}>{venue.venueName}</button>}
    </div>)}
  </section> }))

const EVENT = 'b000864d-c587-448f-8ad6-d314de540d2c'
const event: EventDto = { eventId: EVENT, eventName: 'Map workshop', purpose: 'Workshop', description: null,
  startDatetime: '2027-05-03T02:00:00Z', endDatetime: '2027-05-03T04:00:00Z', expectedAttendance: 50,
  venueId: null, accessibilityNeeds: [], requiredFacilities: [], registrationNeeds: false, organisation: 'Acme',
  venueRequirements: 'Seating', equipmentRequirements: null, status: 'pending', coordinatorName: 'ec1', coordinatorEmail: null }
const option = (id: string, name: string, verdict: VenueOptionDto['verdict'] = 'suitable'): VenueOptionDto => ({
  venue: { venueId: id, venueName: name, venueAddress: `${name} address`, venueCapacity: 100, supportedLayouts: ['theatre'],
    additionalInformation: id === 'a' ? 'Courtyard entrance' : null, venueAccessibilities: [], venueFacilities: [],
    operatingInformation: '', operatingHours: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }],
    latitude: 1.3, longitude: 103.8 },
  verdict, capacityOk: true, spareCapacity: 50, missingAccessibility: [], conflicts: [], reasons: [],
})
const options = [option('a', 'Alpha'), option('b', 'Beta'), option('blocked', 'Closed hall', 'blocked'),
  { ...option('unlocated', 'Unlocated'), venue: { ...option('unlocated', 'Unlocated').venue, latitude: null } }]
function requestPage(config: { replacement?: boolean; read?: () => Response } = {}) {
  seedSession('coordinator')
  const calls = stubApi({
    [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
    [`GET /api/events/${EVENT}/venue-options`]: () => config.read?.() ?? jsonResponse(200, options),
    [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, config.replacement ? [{
      bookingId: 'old', venueId: 'old-venue', venueAddress: 'Original hall', status: 'rejected', rejectReason: 'Unavailable',
    }] : []),
    [`GET /api/events/${EVENT}/venue-assessments`]: () => jsonResponse(200, []),
    [`GET /api/events/${EVENT}/venue-reviews`]: () => jsonResponse(200, []),
    [`POST /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(201, {}),
    [`POST /api/events/${EVENT}/venue-bookings/batch`]: () => jsonResponse(201, []),
  })
  render(<MemoryRouter initialEntries={[`/coordinator/events/${EVENT}/venue-booking${config.replacement ? '?replacesBookingId=old' : ''}`]}>
    <SessionProvider><Routes>
      <Route path="/coordinator/events/:eventId/venue-booking" element={<VenueBookingRequestPage />} />
      <Route path="/coordinator/events/:eventId" element={<p>Event page</p>} />
    </Routes></SessionProvider>
  </MemoryRouter>)
  return calls
}
const catalogue = () => screen.getByRole('list', { name: 'Venues ranked for this event' })
const card = (name: string) => within(catalogue()).getByRole('button', { name: new RegExp(`${name} address`) })
/** Opens a card's details window and adds (or removes) the venue from there. */
function addCard(name: string) {
  fireEvent.click(card(name))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^(Add to request|Remove from request)$/ }))
}
const added = (name: string) => within(card(name)).queryByText('Added')
const map = () => screen.findByRole('region', { name: 'Interactive venue map' })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })

describe('DEV16 EC request map', () => {
  it('DEV16 (happy): right column starts with the map and both map and list use the exact text/unavailable filters', async () => {
    const calls = requestPage()
    const region = await map()
    expect(region.closest('.venue-request__main')?.firstElementChild).toBe(region.closest('.coordinator-venue-map'))
    expect(within(region).getAllByRole('button')).toHaveLength(2)
    expect(card('Unlocated')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search venues' }), { target: { value: ' COURTYARD ' } })
    expect(card('Alpha')).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: 'Location a' })).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: 'Location b' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search venues' }), { target: { value: 'no matching room' } })
    expect(screen.getByText(/No venues match/)).toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search venues' }), { target: { value: '' } })
    fireEvent.click(screen.getByLabelText('Show unavailable venues'))
    expect(card('Closed hall')).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: 'Location blocked' })).toBeInTheDocument()
    expect(calls.some(call => call.method !== 'GET' || call.url.includes('/venue-staff/'))).toBe(false)
  })
  it('DEV16 (happy): card and map selections agree, survive filtering and send the existing batch payload only on submit', async () => {
    const calls = requestPage()
    const region = await map()
    fireEvent.click(within(region).getByRole('button', { name: 'Location a' }))
    expect(within(region).getByRole('checkbox', { name: 'Alpha' })).not.toBeChecked()
    expect(screen.queryByRole('list', { name: 'Selected venues' })).not.toBeInTheDocument()
    addCard('Alpha')
    fireEvent.click(within(region).getByRole('checkbox', { name: 'Beta' }))
    expect(added('Alpha')).toBeInTheDocument()
    expect(added('Beta')).toBeInTheDocument()
    expect(within(region).getByRole('checkbox', { name: 'Alpha' })).toBeChecked()
    expect(within(region).getByRole('checkbox', { name: 'Beta' })).toBeChecked()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search venues' }), { target: { value: 'Beta' } })
    expect(within(region).queryByRole('checkbox', { name: 'Alpha' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Selected venues' })).getAllByRole('listitem')).toHaveLength(2)
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search venues' }), { target: { value: '' } })
    expect(within(region).getByRole('checkbox', { name: 'Alpha' })).toBeChecked()
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Request 2 venues' }))
    await screen.findByText('Event page')
    expect(calls.filter(call => call.method === 'POST')).toEqual([expect.objectContaining({
      url: `/api/events/${EVENT}/venue-bookings/batch`, body: { bookings: [
        expect.objectContaining({ venueId: 'a', startDatetime: '2027-05-03T02:00:00.000Z' }),
        expect.objectContaining({ venueId: 'b', startDatetime: '2027-05-03T02:00:00.000Z' }),
      ] },
    })])
  })
  it('DEV16 (happy): removing selections in the map or review updates both surfaces without submitting', async () => {
    const calls = requestPage()
    const region = await map()
    addCard('Alpha'); addCard('Beta')
    fireEvent.click(within(region).getByRole('checkbox', { name: 'Alpha' }))
    expect(added('Alpha')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Remove selection' }))
    expect(within(region).getByRole('checkbox', { name: 'Beta' })).not.toBeChecked()
    expect(added('Beta')).not.toBeInTheDocument()
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
  it('DEV16 (conflict): replacement map choices retain single-selection semantics and link the replacement source', async () => {
    const calls = requestPage({ replacement: true })
    const region = await map()
    fireEvent.click(within(region).getByRole('checkbox', { name: 'Alpha' }))
    fireEvent.click(within(region).getByRole('checkbox', { name: 'Beta' }))
    expect(within(region).getByRole('checkbox', { name: 'Alpha' })).not.toBeChecked()
    expect(within(region).getByRole('checkbox', { name: 'Beta' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Request replacement Beta address' }))
    await screen.findByText('Event page')
    expect(calls.find(call => call.method === 'POST')?.body).toMatchObject({ venueId: 'b', replacesBookingId: 'old' })
  })
  it('DEV16 (failure): a denied options read cannot display a map or send a venue request', async () => {
    const calls = requestPage({ read: () => jsonResponse(403, { message: 'Access denied' }) })
    await screen.findByRole('heading', { name: "This event isn't assigned to you" })
    expect(screen.queryByRole('region', { name: 'Interactive venue map' })).not.toBeInTheDocument()
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
  it('DEV16 (cross-cutting): live venue results remove outdated markers and keep map/list membership equal', async () => {
    let current = options
    requestPage({ read: () => jsonResponse(200, current) })
    const region = await map()
    current = [options[1]]
    fireEvent.focus(window)
    await waitFor(() => expect(within(region).queryByRole('button', { name: 'Location a' })).not.toBeInTheDocument())
    expect(within(catalogue()).getAllByRole('button')).toHaveLength(1)
    expect(within(region).getByRole('button', { name: 'Location b' })).toBeInTheDocument()
  })
})

describe('DEV16 EC search map', () => {
  it('DEV16 (happy): available search results and map share local address/name/detail filtering', async () => {
    seedSession('coordinator')
    const start = '2027-05-03T10:00', end = '2027-05-03T12:00'
    const params = new URLSearchParams({ startDatetime: new Date(start).toISOString(), endDatetime: new Date(end).toISOString() })
    const calls = stubApi({ [`GET /api/venues/search?${params}`]: () => jsonResponse(200, options.map(o => o.venue)) })
    render(<MemoryRouter><SessionProvider><VenueSearchPage /></SessionProvider></MemoryRouter>)
    const region = await map()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Event starts'), { target: { value: start } })
    fireEvent.change(screen.getByLabelText('Event ends'), { target: { value: end } })
    fireEvent.click(screen.getByRole('button', { name: 'Search venues' }))
    await screen.findByRole('button', { name: 'Alpha address' })
    expect(within(region).getAllByRole('button')).toHaveLength(3)
    expect(screen.getByText('Map location unavailable')).toBeInTheDocument()
    // "Show on map" is in the venue's details window; it closes the window and focuses the pin.
    fireEvent.click(screen.getByRole('button', { name: 'Beta address' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Beta address' })).getByRole('button', { name: 'Show on map' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(region).toHaveAttribute('data-focus', 'b')
    fireEvent.click(within(region).getByRole('button', { name: 'Location a' }))
    expect(screen.getByRole('button', { name: 'Alpha address' })).toHaveAttribute('data-map-focused', 'true')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search by address or details' }), { target: { value: 'courtyard' } })
    expect(screen.getByRole('button', { name: 'Alpha address' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Beta address' })).not.toBeInTheDocument()
    expect(within(region).getAllByRole('button')).toHaveLength(1)
    expect(calls).toHaveLength(1)
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search by address or details' }), { target: { value: 'unmatched' } })
    expect(screen.getByText('No matching venues found.')).toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Required capacity'), { target: { value: '100' } })
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
  })
})
