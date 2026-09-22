import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { VenueCataloguePage } from './VenueCataloguePage'
import { VenueDetailsPage } from './VenueDetailsPage'
import type { VenueDto } from '../../types/venue'

const venue: VenueDto = {
  venueId: 'room-1', venueAddress: 'School A - Classroom 1, Level 2', venueCapacity: 40,
  supportedLayouts: ['classroom', 'boardroom'], venueAccessibilities: [], venueFacilities: [],
  operatingInformation: 'Weekdays 09:00–18:00', additionalInformation: null,
}
const response = (body: unknown, status = 200) => ({ ok: status < 400, status,
  text: async () => JSON.stringify(body) }) as Response
function page(path = '/venue-staff/catalogue') {
  render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/venue-staff/catalogue" element={<VenueCataloguePage />} />
    <Route path="/venue-staff/catalogue/:venueId" element={<VenueDetailsPage />} />
  </Routes></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('keeps rooms at the same location separate and layouts within their room', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([venue,
    { ...venue, venueId: 'room-2', venueAddress: 'School A - Classroom 2, Level 2', venueCapacity: 50 },
  ])))
  page()
  expect(await screen.findByRole('heading', { name: venue.venueAddress })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'School A - Classroom 2, Level 2' })).toBeInTheDocument()
  expect(screen.getByText('40 people')).toBeInTheDocument()
  expect(screen.getByText('50 people')).toBeInTheDocument()
  const links = screen.getAllByRole('link', { name: 'View venue details' })
  expect(links.map(link => link.getAttribute('href'))).toEqual([
    '/venue-staff/catalogue/room-1', '/venue-staff/catalogue/room-2',
  ])
  expect(screen.getAllByLabelText('Supported layouts')).toHaveLength(2)
})

it('reads full characteristics and latest values on refresh and reopening without writes', async () => {
  let saved = venue
  const fetch = vi.fn().mockImplementation(async (url: string) => response(
    url.endsWith('/bookings') ? [] : url.endsWith('/venues') ? [saved] : saved))
  vi.stubGlobal('fetch', fetch)
  page()
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: 'View venue details' }))
  await screen.findByText('No associated bookings.')
  expect(screen.getAllByText('Not recorded')).toHaveLength(2)
  expect(screen.getByText('No Additional Information')).toBeInTheDocument()
  expect(screen.getByText(venue.operatingInformation)).toBeInTheDocument()
  expect(screen.getByText('Classroom')).toBeInTheDocument()
  expect(screen.getByText('Boardroom')).toBeInTheDocument()
  saved = { ...venue, venueCapacity: 75, supportedLayouts: ['theatre'],
    venueAccessibilities: ['none'], venueFacilities: ['projection'],
    operatingInformation: 'Daily', additionalInformation: 'Collect the key at reception.' }
  await user.click(screen.getByRole('button', { name: 'Refresh venue' }))
  expect(await screen.findByText('75 people')).toBeInTheDocument()
  expect(screen.getByText('No accessibility provisions')).toBeInTheDocument()
  expect(screen.getByText('Projection')).toBeInTheDocument()
  expect(screen.getByText('Daily')).toBeInTheDocument()
  expect(screen.getByText('Theatre')).toBeInTheDocument()
  expect(screen.getByText(saved.additionalInformation!)).toBeInTheDocument()
  await user.click(screen.getByRole('link', { name: 'Back to venue catalogue' }))
  expect(await screen.findByText('75 people')).toBeInTheDocument()
  saved = { ...saved, venueCapacity: 80 }
  await user.click(screen.getByRole('button', { name: 'Refresh venues' }))
  expect(await screen.findByText('80 people')).toBeInTheDocument()
  await user.click(screen.getByRole('link', { name: 'View venue details' }))
  expect(await screen.findByText('80 people')).toBeInTheDocument()
  expect(fetch.mock.calls.every(call => call[1].method === 'GET' && call[1].cache === 'no-store')).toBe(true)
})

it.each([403, 404])('hides details and never requests bookings when venue read returns %s', async status => {
  const fetch = vi.fn().mockResolvedValue(response({ message: 'Venue unavailable.' }, status))
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/catalogue/room-1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Venue unavailable.')
  expect(screen.queryByText(venue.venueAddress)).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Associated bookings' })).not.toBeInTheDocument()
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('removes previously displayed catalogue records after access is denied on refresh', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response([venue]))
    .mockResolvedValueOnce(response({ message: 'Access denied.' }, 403)))
  page()
  await screen.findByText(venue.venueAddress)
  await userEvent.click(screen.getByRole('button', { name: 'Refresh venues' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Access denied.')
  expect(screen.queryByText(venue.venueAddress)).not.toBeInTheDocument()
})

it('shows an empty catalogue without venue links', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])))
  page()
  expect(await screen.findByRole('heading', { name: 'No venues yet' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'View venue details' })).not.toBeInTheDocument()
})
