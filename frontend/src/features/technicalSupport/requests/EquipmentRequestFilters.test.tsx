// @stories TS09 TS01 EC-NEW3
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import type { EquipmentRequestStatus } from '../reservations/reservation.types'
import { EquipmentRequestsPage } from './EquipmentRequestsPage'

const requests = (['processing', 'approved', 'attention', 'rejected', 'superseded'] as EquipmentRequestStatus[]).map(status => ({
  requestId: status, eventId: 'event-' + status, eventName: 'Event ' + status, status,
  eventStart: '2030-03-10T01:00:00Z', eventEnd: '2030-03-10T04:00:00Z', technicalRequirement: 'Stage',
  items: [{ equipmentId: 'mic', equipmentName: 'Microphone', quantity: 3 }],
}))

function Location() { const location = useLocation(); return <output aria-label="location">{location.search}</output> }
function open(status: string) {
  seedSession('technical-support')
  render(<MemoryRouter initialEntries={['/technical-support/requests?status=' + status]}><SessionProvider><Routes>
    <Route path="/technical-support/requests" element={<EquipmentRequestsPage />} />
  </Routes><Location /></SessionProvider></MemoryRouter>)
}
const nav = () => within(screen.getByRole('group', { name: 'Filter equipment requests by status' }))

afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })

describe('TS09 TS01 EC-NEW3 equipment status navigation', () => {
  it.each([
    ['processing', 'all', ['processing']],
    ['approved', 'all', ['approved', 'attention']],
    ['attention', 'all', ['attention']],
    ['rejected', 'all', ['rejected']],
    ['superseded', 'all', ['superseded']],
    ['all', 'all', ['processing', 'approved', 'attention', 'rejected', 'superseded']],
  ] as [string, string, string[]][])('TS09 (happy): %s filters the %s response locally and preserves its request quantities', async (status, backend, expected) => {
    const calls = stubApi({ ['GET /api/equipment-requests?status=' + backend]: () => jsonResponse(200, requests) })
    open(status)
    const list = await screen.findByRole('list', { name: 'Equipment requests' })
    expect(within(list).getAllByRole('link').map(link => link.getAttribute('href')))
      .toEqual(expected.map(value => '/technical-support/requests/' + value))
    expect(list).toHaveTextContent('3 × Microphone')
    expect(nav().getAllByRole('button', { pressed: true })).toHaveLength(1)
    expect(calls.map(call => call.url)).toEqual(['/api/equipment-requests?status=' + backend])
  })

  it('TS09 EC-NEW3 (cross-cutting): counts use the returned data, badges change the URL and reassessment stays selected after refresh', async () => {
    let current = requests
    const calls = stubApi({ 'GET /api/equipment-requests?status=all': () => jsonResponse(200, current) })
    open('all')
    await screen.findByRole('link', { name: 'Event processing' })
    expect(nav().getByRole('button', { name: 'All 5' })).toHaveAttribute('aria-pressed', 'true')
    expect(nav().getByRole('button', { name: 'Approved 2' })).toBeInTheDocument()
    fireEvent.click(nav().getByRole('button', { name: 'Needs reassessment 1' }))
    await waitFor(() => expect(screen.getByLabelText('location')).toHaveTextContent('?status=attention'))
    expect(await screen.findByRole('link', { name: 'Event attention' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Event processing' })).not.toBeInTheDocument()
    current = requests.filter(request => request.status !== 'attention')
    fireEvent.focus(window)
    expect(await screen.findByText('No equipment requests match this status.')).toBeInTheDocument()
    expect(nav().getByRole('button', { name: 'Needs reassessment 0' })).toHaveAttribute('aria-pressed', 'true')
    expect(nav().getByRole('button', { name: 'All 4' })).toBeInTheDocument()
    expect(calls.every(call => call.url === '/api/equipment-requests?status=all')).toBe(true)
  })

  it('TS09 (failure): a failed reassessment read keeps the filter and does not invent zero counts', async () => {
    stubApi({ 'GET /api/equipment-requests?status=all': () => jsonResponse(500, { message: 'Offline' }) })
    open('attention')
    expect(await screen.findByRole('alert')).toHaveTextContent('Offline')
    expect(nav().getByRole('button', { name: 'Needs reassessment' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('list', { name: 'Equipment requests' })).not.toBeInTheDocument()
  })
})
