// @stories VS07 VS17
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { VenueEditPage } from './VenueEditPage'
import type { VenueDto } from '../../types/venue'

const venue: VenueDto = { venueId: 'v1', venueAddress: 'Main Hall', venueCapacity: 50,
  supportedLayouts: ['theatre'], venueAccessibilities: [], venueFacilities: [],
  operatingInformation: 'Legacy text must not display', operatingHours: [{dayOfWeek:1,openTime:'09:00',closeTime:'18:00'}], additionalInformation: null }
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
function Origin() {
  const location = useLocation()
  return <><p aria-label="Current page">{location.pathname + location.search + location.hash}</p><VenueDetailsPanel venue={venue} /></>
}
function page(path: string, state?: object) {
  render(<MemoryRouter initialEntries={[{ pathname: path.split('?')[0], search: path.includes('?') ? '?' + path.split('?')[1] : '', state }]}><Routes>
    <Route path="/venue-staff/catalogue/:venueId/edit" element={<VenueEditPage />} />
    <Route path="/venue-staff/catalogue" element={<Origin />} />
    <Route path="/venue-staff/catalogue/:venueId" element={<Origin />} />
    <Route path="/venue-staff/bookings/:bookingId" element={<Origin />} />
  </Routes></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('VS07 venue edit return navigation', () => {
  it.each(['/venue-staff/catalogue', '/venue-staff/catalogue/v1', '/venue-staff/bookings/b1?from=booking-approvals'])('VS07 (happy): save returns to %s instead of always opening the catalogue', async path => {
    const fetcher = vi.fn(async () => json(venue)); vi.stubGlobal('fetch', fetcher)
    page(path)
    await userEvent.click(screen.getByRole('link', { name: 'Edit venue' }))
    const capacity = await screen.findByLabelText('Overall capacity')
    await userEvent.clear(capacity); await userEvent.type(capacity, '60')
    await userEvent.click(screen.getByRole('button', { name: 'Save venue' }))
    expect(await screen.findByLabelText('Current page')).toHaveTextContent(path)
    expect(fetcher).toHaveBeenCalledWith('/api/venues/v1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ venueCapacity: 60 }) }))
  })
  it('VS07 (happy): cancel returns to the originating booking without saving', async () => {
    const fetcher = vi.fn(async () => json(venue)); vi.stubGlobal('fetch', fetcher)
    const path = '/venue-staff/bookings/b1?from=booking-approvals'
    page(path); await userEvent.click(screen.getByRole('link', { name: 'Edit venue' }))
    await screen.findByLabelText('Overall capacity')
    await userEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    expect(await screen.findByLabelText('Current page')).toHaveTextContent(path)
    expect(fetcher.mock.calls).toHaveLength(1)
  })
  it.each([undefined, { returnTo: 'https://example.com' }, { returnTo: '/venue-staff/catalogue/v1/edit' }])('VS07 (boundary): direct edit or invalid return state falls back to the catalogue', async state => {
    vi.stubGlobal('fetch', vi.fn(async () => json(venue)))
    page('/venue-staff/catalogue/v1/edit', state)
    await screen.findByLabelText('Overall capacity')
    await userEvent.click(screen.getByRole('button', { name: 'Save venue' }))
    expect(await screen.findByLabelText('Current page')).toHaveTextContent('/venue-staff/catalogue')
  })
  it('VS07 (failure): a failed save remains on the edit form with input preserved', async () => {
    vi.stubGlobal('fetch', vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => init?.method === 'PUT' ? json({ message: 'Save unavailable' }, 503) : json(venue)))
    page('/venue-staff/catalogue/v1'); await userEvent.click(screen.getByRole('link', { name: 'Edit venue' }))
    const capacity = await screen.findByLabelText('Overall capacity')
    await userEvent.clear(capacity); await userEvent.type(capacity, '60')
    await userEvent.click(screen.getByRole('button', { name: 'Save venue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Save unavailable')
    expect(capacity).toHaveValue(60)
    expect(screen.queryByLabelText('Current page')).not.toBeInTheDocument()
  })
})
