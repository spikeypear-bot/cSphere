// @stories DEV16 AU04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { VenueMapExplorer } from './VenueMapExplorer'
import { VenueStaffHomePage } from '../venueStaff/VenueStaffHomePage'
import { jsonResponse } from '../../test/apiStubs'
import type { VenueDto } from '../../types/venue'
import type { VenueMapActivity, VenueMapBooking } from './mapData'

const mapEvents = vi.hoisted(() => ({ select: undefined as ((id: string) => void) | undefined }))
vi.mock('./VenueMap', () => ({ default: ({ venues, selectedId, onSelect }: { venues: VenueDto[]; selectedId?: string; onSelect: (id: string) => void }) => {
  mapEvents.select = onSelect
  return <div role="region" aria-label="Interactive venue map">{venues.filter(v => v.latitude != null).map(v => <button key={v.venueId}
    aria-pressed={selectedId === v.venueId} onClick={() => onSelect(v.venueId)}>Marker {v.venueName}</button>)}</div>
} }))

const venues: VenueDto[] = ['Alpha', 'Beta', 'Missing'].map((name, i) => ({
  venueId: `v${i}`, venueName: name, venueAddress: `Building ${i}`, venueCapacity: i === 1 ? 50 : 200,
  supportedLayouts: ['theatre'], venueAccessibilities: [], venueFacilities: i === 1 ? [] : ['projection'],
  operatingInformation: '', operatingHours: [], additionalInformation: null, latitude: i === 2 ? null : 1.3, longitude: 103.8,
}))
const booking: VenueMapBooking = { bookingId: 'b1', eventId: 'e1', eventName: 'Conference', status: 'approved',
  startDatetime: '2027-05-01T01:00:00Z', endDatetime: '2027-05-01T03:00:00Z', holdExpiresAt: null,
  serverTime: '2027-04-01T00:00:00Z', past: false }
function setup(options: { home?: boolean; readVenues?: () => Response; readBookings?: () => Response | Promise<Response>; readActivity?: (url: string) => Response } = {}) {
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/venues') return options.readVenues?.() ?? jsonResponse(200, venues)
    if (url.startsWith('/api/venue-staff/map/activity')) return options.readActivity?.(url) ?? jsonResponse(200, [
      { venueId: 'v0', eventCount: 1, pendingBookingCount: 0, matchingBookingCount: 1 },
    ] satisfies VenueMapActivity[])
    if (url.includes('/bookings?')) return options.readBookings?.() ?? jsonResponse(200, [booking])
    throw new Error(`Unexpected request ${url}`)
  })
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter>{options.home ? <VenueStaffHomePage /> : <VenueMapExplorer />}</MemoryRouter>)
  return fetch
}
async function catalogue() { return screen.findByRole('button', { name: /Alpha.*Building 0/ }) }
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('DEV16 Venue Staff map', () => {
  it('DEV16 (happy): console keeps workflow cards above the map and fetches no booking details initially', async () => {
    const fetch = setup({ home: true })
    await catalogue()
    expect(screen.getByLabelText('Minimum capacity')).toHaveAttribute('placeholder', 'e.g. 100')
    expect(screen.getByLabelText('Minimum capacity')).toHaveValue(null)
    expect(screen.getByRole('link', { name: /Venue catalogue/ })).toHaveAttribute('href', '/venue-staff/catalogue')
    const consoleHeading = screen.getByRole('heading', { name: 'Venue Staff console' })
    expect(consoleHeading.compareDocumentPosition(screen.getByRole('heading', { name: 'Explore venues' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(fetch.mock.calls.map(c => String(c[0]))).toEqual(['/api/venues', '/api/venue-staff/map/activity'])
  })
  it('DEV16 (happy): catalogue and marker selection agree and booking links use distinct booking IDs', async () => {
    setup({ readBookings: () => jsonResponse(200, [booking, { ...booking, bookingId: 'b2' }]) })
    fireEvent.click(await catalogue())
    expect(await screen.findByRole('button', { name: 'Marker Alpha' })).toHaveAttribute('aria-pressed', 'true')
    const panel = await screen.findByRole('region', { name: 'Selected venue details' })
    expect(await within(panel).findAllByRole('link', { name: 'View booking details' })).toHaveLength(2)
    expect(within(panel).getAllByRole('link', { name: 'View booking details' }).map(a => a.getAttribute('href'))).toEqual(['/venue-staff/bookings/b1', '/venue-staff/bookings/b2'])
    expect(within(panel).queryByRole('link', { name: 'Edit venue' })).not.toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'Map venue catalogue' })
    list.scrollTop = 100
    vi.spyOn(list, 'getBoundingClientRect').mockReturnValue({ top: 100 } as DOMRect)
    vi.spyOn(screen.getByRole('button', { name: /Beta.*Building 1/ }), 'getBoundingClientRect').mockReturnValue({ top: 250 } as DOMRect)
    fireEvent.click(screen.getByRole('button', { name: 'Marker Beta' }))
    expect(screen.getByRole('button', { name: /Beta.*Building 1/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /Alpha.*Building 0/ })).toHaveAttribute('aria-pressed', 'false')
    // The card begins 150px below the catalogue top; reveal it within the existing scroll position.
    expect(list.scrollTop).toBeGreaterThanOrEqual(150)
  })
  it('DEV16 (happy): combined search/capacity/facility/date filters affect list and map and clear together', async () => {
    const fetch = setup()
    await catalogue()
    fireEvent.change(screen.getByLabelText('Search venues'), { target: { value: 'Alpha' } })
    fireEvent.change(screen.getByLabelText('Minimum capacity'), { target: { value: '200' } })
    expect(screen.getByRole('group', { name: 'Required facilities' })).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Projection'))
    fireEvent.change(screen.getByLabelText('Events on date (SGT)'), { target: { value: '2027-05-01' } })
    await catalogue()
    expect(screen.queryByRole('button', { name: /Beta.*Building/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Marker Beta' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.map(c => String(c[0]))).toContain('/api/venue-staff/map/activity?date=2027-05-01')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByRole('button', { name: /Beta.*Building/ })).toBeInTheDocument()
    expect(screen.getByLabelText('Minimum capacity')).toHaveValue(null)
    expect(screen.getByLabelText('Projection')).not.toBeChecked()
  })
  it('DEV16 (boundary): missing map coordinates preserve selectable venue details and no-booking state', async () => {
    setup({ readBookings: () => jsonResponse(200, []) })
    fireEvent.click(await screen.findByRole('button', { name: /Missing.*Building 2/ }))
    expect(await screen.findByText('No upcoming bookings for this venue.')).toBeInTheDocument()
    expect(screen.getByText('Map location unavailable. Venue details and bookings are still available.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Marker Missing' })).not.toBeInTheDocument()
  })
  it('DEV16 (boundary): invalid capacity and no-match filters cannot leave old markers or selection visible', async () => {
    setup()
    await catalogue()
    fireEvent.change(screen.getByLabelText('Minimum capacity'), { target: { value: '1.5' } })
    expect(screen.getByRole('alert')).toHaveTextContent('whole number')
    expect(screen.queryByRole('button', { name: 'Marker Alpha' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    fireEvent.click(await catalogue())
    await screen.findByRole('region', { name: 'Selected venue details' })
    fireEvent.change(screen.getByLabelText('Search venues'), { target: { value: 'unknown' } })
    expect(screen.getByText('No matching venues')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Selected venue details' })).not.toBeInTheDocument()
  })
  it('DEV16 (conflict): a queued marker selection after capacity validation hides the catalogue cannot reopen stale details', async () => {
    setup()
    await catalogue()
    const queuedSelection = mapEvents.select!
    fireEvent.change(screen.getByLabelText('Minimum capacity'), { target: { value: '1.5' } })
    act(() => queuedSelection('v0'))
    expect(screen.getByRole('alert')).toHaveTextContent('Minimum capacity must be a whole number')
    expect(screen.queryByRole('button', { name: 'Marker Alpha' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Selected venue details' })).not.toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Map venue catalogue' }).scrollTop).toBe(0)
  })
  it('DEV16 (failure): failed reads have retry and never leave denied bookings displayed', async () => {
    let denied = false
    let delay = false
    let resolveRead: ((response: Response) => void) | undefined
    setup({ readBookings: () => delay ? new Promise<Response>(resolve => { resolveRead = resolve })
      : jsonResponse(denied ? 403 : 200, denied ? { message: 'Access denied' } : [booking]) })
    fireEvent.click(await catalogue())
    await screen.findByText('Conference')
    denied = true
    fireEvent.click(screen.getByRole('button', { name: 'Refresh bookings' }))
    await screen.findByRole('button', { name: 'Retry bookings' })
    expect(screen.queryByText('Conference')).not.toBeInTheDocument()
    denied = false
    delay = true
    fireEvent.click(screen.getByRole('button', { name: 'Retry bookings' }))
    expect(screen.queryByText('Conference')).not.toBeInTheDocument()
    await act(async () => { resolveRead!(jsonResponse(200, [booking])) })
    expect(await screen.findByText('Conference')).toBeInTheDocument()
  })
  it('DEV16 (failure): catalogue errors recover without booking fan-out', async () => {
    let fail = true
    setup({ readVenues: () => jsonResponse(fail ? 503 : 200, fail ? { message: 'Try again later' } : venues) })
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again later')
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await catalogue()).toBeInTheDocument()
  })
  it('DEV16 (cross-cutting): refresh removes saved bookings and an invalidated venue selection', async () => {
    let currentVenues = venues
    let rows = [booking]
    setup({ readVenues: () => jsonResponse(200, currentVenues), readBookings: () => jsonResponse(200, rows) })
    fireEvent.click(await catalogue())
    await screen.findByText('Conference')
    rows = []
    fireEvent.focus(window)
    expect(await screen.findByText('No upcoming bookings for this venue.')).toBeInTheDocument()
    expect(screen.queryByText('Conference')).not.toBeInTheDocument()
    currentVenues = venues.filter(v => v.venueId !== 'v0')
    fireEvent.focus(window)
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Selected venue details' })).not.toBeInTheDocument())
  })
  it('DEV16 (boundary): expired holds stop displaying tentative even when refresh fails', async () => {
    let fail = false
    vi.useFakeTimers()
    await act(async () => { setup({ readBookings: () => jsonResponse(fail ? 503 : 200, fail ? { message: 'Unavailable' } : [{ ...booking, status: 'pending', holdExpiresAt: '2027-04-01T00:00:01Z' }]) }) })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Alpha.*Building 0/ })) })
    expect(screen.getByText('Tentative Hold')).toBeInTheDocument()
    fail = true
    await act(async () => { await vi.advanceTimersByTimeAsync(1100) })
    expect(screen.queryByText('Tentative Hold')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry bookings' })).toBeInTheDocument()
  })
  it('DEV16 (happy): view switches, facility removal and closing details preserve the catalogue', async () => {
    setup()
    await catalogue()
    fireEvent.click(screen.getByRole('button', { name: 'Map view' }))
    expect(screen.getByRole('button', { name: 'Map view' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'List view' })).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'List view' }))
    expect(screen.getByRole('button', { name: 'List view' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByLabelText('Projection'))
    expect(screen.queryByRole('button', { name: /Beta.*Building/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Projection'))
    expect(screen.getByRole('button', { name: /Beta.*Building/ })).toBeInTheDocument()
    fireEvent.click(await catalogue())
    await screen.findByText('Conference')
    fireEvent.click(screen.getByRole('button', { name: 'Close details' }))
    expect(screen.queryByRole('region', { name: 'Selected venue details' })).not.toBeInTheDocument()
    expect(await catalogue()).toHaveAttribute('aria-pressed', 'false')
  })
  it('DEV16 (boundary): unrecorded capacity and singular/plural pending requests have clear labels', async () => {
    setup({ readVenues: () => jsonResponse(200, venues.map(v => ({ ...v, venueCapacity: null }))),
      readActivity: () => jsonResponse(200, [
        { venueId: 'v0', eventCount: 1, pendingBookingCount: 1, matchingBookingCount: 1 },
        { venueId: 'v1', eventCount: 0, pendingBookingCount: 2, matchingBookingCount: 2 },
      ]) })
    await catalogue()
    expect(screen.getAllByText(/Capacity not recorded/)).toHaveLength(3)
    expect(screen.getByText('1 pending booking request')).toBeInTheDocument()
    expect(screen.getByText('2 pending booking requests')).toBeInTheDocument()
  })
  it('DEV16 (happy): past approved bookings are opt-in and historical date details use the selected day', async () => {
    const fetch = setup({ readBookings: () => jsonResponse(200, [{ ...booking, past: true }]) })
    fireEvent.click(await catalogue())
    expect(await screen.findByRole('heading', { name: 'Past approved bookings' })).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Show past approved bookings'))
    await waitFor(() => expect(fetch.mock.calls.map(c => String(c[0]))).toContain('/api/venue-staff/map/venues/v0/bookings?includePast=true'))
    fireEvent.change(screen.getByLabelText('Events on date (SGT)'), { target: { value: '2027-05-01' } })
    await waitFor(() => expect(fetch.mock.calls.map(c => String(c[0]))).toContain('/api/venue-staff/map/venues/v0/bookings?includePast=false&date=2027-05-01'))
    expect(screen.queryByLabelText('Show past approved bookings')).not.toBeInTheDocument()
  })
  it('DEV16 (boundary): an empty catalogue and a date without bookings show their distinct empty states', async () => {
    let saved = [] as VenueDto[]
    setup({ readVenues: () => jsonResponse(200, saved), readBookings: () => jsonResponse(200, []) })
    expect(await screen.findByRole('heading', { name: 'No venues yet' })).toBeInTheDocument()
    saved = venues
    fireEvent.click(screen.getByRole('button', { name: 'Refresh venues' }))
    await catalogue()
    fireEvent.change(screen.getByLabelText('Events on date (SGT)'), { target: { value: '2027-05-01' } })
    fireEvent.click(await catalogue())
    expect(await screen.findByText('No bookings on this date.')).toBeInTheDocument()
  })
})
