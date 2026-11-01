// @stories VS17 VS15 VS16 VS18 VS-C3 VS02
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { jsonResponse, seedSession } from '../../test/apiStubs'
import { SessionProvider } from '../../lib/session'
import { VenueStaffHomePage } from './VenueStaffHomePage'
import { VenueDetailsPage } from './VenueDetailsPage'
const json = (body: unknown) => ({ok:true, status:200, text:async()=>JSON.stringify(body)}) as Response
const venue = {venueId:'v1', venueAddress:'Main Hall', venueCapacity:200, supportedLayouts:['theatre'], venueAccessibilities:[], venueFacilities:[], operatingHours:[]}
const event = {eventId:'e1', eventName:'Conference', startDatetime:'2027-03-10T01:00:00Z', endDatetime:'2027-03-10T04:00:00Z',expectedAttendance:100}
function details(getBookings: () => unknown[]) {
 vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
  const url=String(input)
  return json(url.endsWith('/bookings') ? getBookings() : url.endsWith('/venues/v1') ? venue : url.endsWith('/occupancy-settings') ? {setupMinutes:0,turnaroundMinutes:0} : [])
 }))
 render(<MemoryRouter initialEntries={['/venue-staff/catalogue/v1']}><Routes><Route path="/venue-staff/catalogue/:venueId" element={<VenueDetailsPage/>}/></Routes></MemoryRouter>)
}
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.localStorage.clear()})
describe('VS17 Venue Staff presentation',()=>{
 it('VS17 VS02 (happy): home greets the staff member and links to booking approvals without developer scaffold text',async()=>{
  // The other pages are reached from the side navigation (VenueStaffNav.test.tsx).
  seedSession('venue-staff');vi.stubGlobal('fetch',vi.fn(async()=>jsonResponse(200,[])))
  render(<MemoryRouter><SessionProvider><VenueStaffHomePage/></SessionProvider></MemoryRouter>)
  expect(screen.getByRole('heading',{level:1})).toHaveTextContent(/^Good \w+, vs1$/)
  expect(screen.getByRole('link',{name:'View all booking requests'})).toHaveAttribute('href','/venue-staff/booking-approvals')
  expect(await screen.findByText('No booking requests waiting for review.')).toBeInTheDocument()
  expect(screen.queryByText(/Skeleton|None of these pages|backend package|Sprint story|live backlog|VS06A/)).not.toBeInTheDocument()
 })
 it.each([['approved','Approved'],['attention','Requires attention'],['changed','Replaced'],['rejected','Rejected'],['cancelled','Cancelled'],['pending','Pending']])('VS17 (cross-cutting): associated booking status %s has the human label %s',async(status,label)=>{
  details(()=>[{bookingId:'b1',status,event}])
  const region=await screen.findByRole('region',{name:'Associated bookings'})
  expect(await within(region).findByText(label)).toHaveAttribute('data-booking-status',status)
  expect(within(region).getByRole('link',{name:'View booking details'})).toHaveAttribute('href','/venue-staff/bookings/b1')
 })
 it('VS17 (happy): associated booking status refreshes on focus without reloading the venue',async()=>{
  let status='approved';details(()=>[{bookingId:'b1',status,event}])
  const region=await screen.findByRole('region',{name:'Associated bookings'})
  await within(region).findByText('Approved');status='attention';fireEvent.focus(window)
  expect(await within(region).findByText('Requires attention')).toBeInTheDocument()
  expect(within(region).queryByText('Approved')).not.toBeInTheDocument()
 })
 it('VS-C3 (happy): an active hold is labelled tentative without changing the pending booking',async()=>{
  const serverTime=new Date().toISOString()
  const hold={holdId:'h1',status:'active',serverTime,expiresAt:new Date(Date.now()+3600000).toISOString()}
  const booking={bookingId:'b1',status:'pending',event,holds:[hold]};details(()=>[booking])
  const region=await screen.findByRole('region',{name:'Associated bookings'})
  expect(await within(region).findByText('Tentative Hold')).toHaveAttribute('data-booking-status','tentative')
  expect(booking.status).toBe('pending')
 })
})
