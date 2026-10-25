// @stories VS17 VS07 EC03 EO04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { VenueDetailsPanel } from '../venueStaff/VenueDetailsPanel'
import { VenueBookingRequestPage } from '../coordinator/VenueBookingRequestPage'
import { EventDetailsPage } from './EventDetailsPage'
import { OrganiserArrangements } from './OrganiserArrangements'
import type { VenueDto } from '../../types/venue'
const json = (body: unknown) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) }) as Response
const makeVenue = (summary: string): VenueDto => ({ venueId: 'v1', venueAddress: 'Hours Hall', venueCapacity: 200, supportedLayouts: ['theatre'],
 venueAccessibilities: [], venueFacilities: [], additionalInformation: null, operatingInformation: 'fea',
 operatingHours: summary === 'Operating hours not configured' ? [] : [{ dayOfWeek: 3, openTime: summary.includes('08:30') ? '08:30' : '08:00', closeTime: '18:00' }] })
const event = { eventId: 'e1', eventName: 'Hours event', status: 'pending', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
 expectedAttendance: 100, accessibilityNeeds: [], requiredFacilities: [], venueRequirements: 'Theatre seating' }
function eo(summary: string) { return { ...event, view: 'confirmed', accessibilityNeeds: [], coordinatorName: 'ec1', coordinatorEmail: null, equipment: [],
 venues: [{ venueId: 'v1', name: 'Hours Hall', capacity: 200, layouts: ['theatre'], facilities: [], accessibilities: [], operatingInformation: 'fea', operatingHours: makeVenue(summary).operatingHours, noLongerAvailable: false }],
 firstConfirmedAt: '2026-10-01T01:00:00Z', confirmedAt: '2026-10-01T01:00:00Z' } }
function coordinator(getVenue: () => VenueDto, existing = false, history = false) {
 window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'test', refreshToken: 'test', userId: 'ec1', username: 'ec1', role: 'coordinator', organisation: 'ConnectSphere' }))
 vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
  const url = String(input)
  return json(url.endsWith('/readiness') ? { approvalStatus: 'not_reviewed', items: [], completedItems: 0, totalItems: 0 } : url.endsWith('/venue-options') ? [{ venue: getVenue(), verdict: 'suitable', capacityOk: true, spareCapacity: 100, missingAccessibility: [], missingFacilities: [], unavailablePeriods: [], conflicts: [], reasons: [] }]
   : url.endsWith('/venue-bookings') && (existing || history) ? [{ bookingId: 'b1', status: history ? 'rejected' : 'approved', venueId: 'v1', venueAddress: 'Hours Hall', operatingInformation: getVenue().operatingInformation, operatingHours: getVenue().operatingHours }] : url.endsWith('/events/e1') ? event : [])
 }))
 render(<MemoryRouter initialEntries={[existing ? '/coordinator/events/e1' : '/coordinator/events/e1/venue-booking']}><SessionProvider><Routes>
  <Route path="/coordinator/events/:eventId" element={<EventDetailsPage />} />
  <Route path="/coordinator/events/:eventId/venue-booking" element={<VenueBookingRequestPage />} />
 </Routes></SessionProvider></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.localStorage.clear() })
describe('VS17 operating hours across role displays', () => {
 it.each(['Wednesday 08:00–18:00', 'Operating hours not configured'])('VS17 (cross-cutting): staff display the canonical schedule: %s', summary => {
  render(<MemoryRouter><VenueDetailsPanel venue={makeVenue(summary)} /></MemoryRouter>)
  expect(screen.getByText('Operating hours', { selector: 'dt' })).toBeInTheDocument()
  expect(screen.getByText(summary === 'Operating hours not configured' ? summary : '8am – 6pm')).toBeInTheDocument()
  expect(screen.queryByText('fea')).not.toBeInTheDocument()
 })
 it.each(['Wednesday 08:00–18:00', 'Operating hours not configured'])('VS17 (cross-cutting): organisers display operating hours under the correct label: %s', async summary => {
  vi.stubGlobal('fetch', vi.fn(async () => json(eo(summary))))
  render(<OrganiserArrangements eventId="e1" />)
  const venues = await screen.findByRole('region', { name: 'Venues' })
  expect(within(venues).getByText('Operating hours', { selector: 'dt' })).toBeInTheDocument()
  expect(within(venues).getByText(summary === 'Operating hours not configured' ? summary : '8am – 6pm')).toBeInTheDocument()
  expect(within(venues).queryByText('fea')).not.toBeInTheDocument()
  expect(screen.queryByText('Good to know')).not.toBeInTheDocument()
 })
 it('VS17 (happy): coordinator details show one current schedule and refresh it without navigation', async () => {
  let venue = makeVenue('Wednesday 08:00–18:00'); coordinator(() => venue)
  await userEvent.click(await screen.findByRole('button', { name: /Hours Hall/ }))
  const details = screen.getByRole('region', { name: 'Selected venue details' })
  expect(within(details).getByText('8am – 6pm')).toBeInTheDocument()
  expect(within(details).getAllByText('Operating hours', { selector: 'dt' })).toHaveLength(1)
  expect(screen.queryByText('Structured weekly hours')).not.toBeInTheDocument()
  expect(within(screen.getByRole('table')).getByText('8am \u2013 6pm')).toBeInTheDocument()
  expect(screen.queryByText('fea')).not.toBeInTheDocument()
  venue = makeVenue('Wednesday 08:30–18:00'); fireEvent.focus(window)
  expect(await within(details).findByText('8:30am – 6pm')).toBeInTheDocument()
  expect(within(details).queryByText('8am – 6pm')).not.toBeInTheDocument()
 })
 it('VS17 (happy): coordinator existing bookings also show live operating hours', async () => {
  let venue = makeVenue('Wednesday 08:00–18:00'); coordinator(() => venue, true)
  expect(await screen.findByText('8am – 6pm')).toBeInTheDocument()
  expect(screen.getByText('Operating hours', { selector: 'dt' })).toBeInTheDocument()
  venue = makeVenue('Wednesday 08:30–18:00'); fireEvent.focus(window)
  expect(await screen.findByText('8:30am – 6pm')).toBeInTheDocument()
 })
 it('VS17 (happy): organiser hours update on focus while the arrangements remain open', async () => {
  let summary = 'Wednesday 08:00–18:00'; vi.stubGlobal('fetch', vi.fn(async () => json(eo(summary))))
  render(<OrganiserArrangements eventId="e1" />); await screen.findByText('8am – 6pm')
  summary = 'Wednesday 08:30–18:00'; fireEvent.focus(window)
  expect(await screen.findByText('8:30am – 6pm')).toBeInTheDocument()
  expect(screen.queryByText('8am – 6pm')).not.toBeInTheDocument()
 })
})

it('VS17 (cross-cutting): coordinator request history renders structured rows even when legacy prose disagrees',async()=>{
 coordinator(()=>makeVenue('Wednesday 08:00'),false,true)
 const history=(await screen.findByRole('heading',{name:'Earlier requests for this event'})).closest('.venue-request__history') as HTMLElement
 expect(within(history).getByText('Wednesday')).toBeInTheDocument()
 expect(within(history).getByText('8am \u2013 6pm')).toBeInTheDocument()
 expect(within(history).queryByText('fea')).not.toBeInTheDocument()
})
