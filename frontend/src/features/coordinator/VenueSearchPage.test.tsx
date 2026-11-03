// @stories EC04 VS17
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { VenueDto } from '../../types/venue'
import { VenueSearchPage } from './VenueSearchPage'

const start = '2027-03-10T09:00'
const end = '2027-03-10T12:00'

const match: VenueDto = {
  venueId: 'venue-1',
  venueAddress: '1 Harbour Road',
  venueCapacity: 100,
  supportedLayouts: ['theatre'],
  venueAccessibilities: [],
  venueFacilities: ['stage', 'projection'],
  operatingInformation: 'Legacy text must never be advertised',
  operatingHours: [{dayOfWeek:3,openTime:'10:30',closeTime:'23:30'}],
  additionalInformation: null,
}

function searchUrl(capacity = '50') {
  const params = new URLSearchParams({
    startDatetime: new Date(start).toISOString(),
    endDatetime: new Date(end).toISOString(),
  })
  params.set('capacity', capacity)
  params.append('facility', 'stage')
  params.append('facility', 'projection')
  return `/api/venues/search?${params.toString()}`
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SessionProvider>
        <VenueSearchPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function enterPeriod() {
  fireEvent.change(screen.getByLabelText('Event starts'), { target: { value: start } })
  fireEvent.change(screen.getByLabelText('Event ends'), { target: { value: end } })
}

describe('EC04 VenueSearchPage', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('searches with event time, capacity and all selected facilities', async () => {
    const calls = stubApi({ [`GET ${searchUrl()}`]: () => jsonResponse(200, [match]) })
    const user = userEvent.setup()
    renderPage()
    enterPeriod()
    fireEvent.change(screen.getByLabelText('Required capacity'), { target: { value: '50' } })
    await user.click(screen.getByLabelText('Stage'))
    await user.click(screen.getByLabelText('Projection'))
    await user.click(screen.getByRole('button', { name: 'Search venues' }))

    // A short card; opening hours and booking timings are in its details window.
    const card = await screen.findByRole('button', { name: '1 Harbour Road' })
    expect(within(card).getByText(/100 people/)).toBeInTheDocument()
    expect(screen.queryByText('Wednesday')).not.toBeInTheDocument()
    await user.click(card)
    const details = screen.getByRole('dialog', { name: '1 Harbour Road' })
    expect(within(details).getByText('Wednesday')).toBeInTheDocument()
    expect(within(details).getByText('10:30am \u2013 11:30pm')).toBeInTheDocument()
    expect(within(details).getByText('Booking Window')).toBeInTheDocument()
    expect(screen.queryByText('Legacy text must never be advertised')).not.toBeInTheDocument()
    const url = new URL(calls[0].url, 'http://localhost')
    expect(url.searchParams.get('startDatetime')).toBe(new Date(start).toISOString())
    expect(url.searchParams.get('endDatetime')).toBe(new Date(end).toISOString())
    expect(url.searchParams.get('capacity')).toBe('50')
    expect(url.searchParams.getAll('facility')).toEqual(['stage', 'projection'])
  })

  it('shows a no-matches message when the server returns no venues', async () => {
    stubApi({ [`GET ${searchUrl()}`]: () => jsonResponse(200, []) })
    const user = userEvent.setup()
    renderPage()
    enterPeriod()
    fireEvent.change(screen.getByLabelText('Required capacity'), { target: { value: '50' } })
    await user.click(screen.getByLabelText('Stage'))
    await user.click(screen.getByLabelText('Projection'))
    await user.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(await screen.findByRole('status')).toHaveTextContent('No matching venues found.')
  })

  it('rejects invalid event times without requesting or showing stale results', async () => {
    const calls = stubApi({})
    const user = userEvent.setup()
    renderPage()
    fireEvent.change(screen.getByLabelText('Event starts'), { target: { value: end } })
    fireEvent.change(screen.getByLabelText('Event ends'), { target: { value: start } })
    await user.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(screen.getByRole('alert')).toHaveTextContent('End date and time must be after')
    expect(calls).toHaveLength(0)
    expect(screen.queryByRole('heading', { name: 'Search results' })).not.toBeInTheDocument()
  })

  it('rejects an impossible calendar date without requesting venues', async () => {
    const calls = stubApi({})
    const user = userEvent.setup()
    renderPage()
    fireEvent.change(screen.getByLabelText('Event starts'), { target: { value: '2027-02-30T09:00' } })
    fireEvent.change(screen.getByLabelText('Event ends'), { target: { value: end } })
    await user.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid start and end date and time.')
    expect(calls).toHaveLength(0)
  })

  it('rejects a negative or non-integer capacity without requesting venues', async () => {
    const calls = stubApi({})
    const user = userEvent.setup()
    renderPage()
    enterPeriod()
    fireEvent.change(screen.getByLabelText('Required capacity'), { target: { value: '-1' } })
    await user.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Required capacity must be a whole number greater than 0.')
    expect(calls).toHaveLength(0)
  })
})

describe('EC04 VenueSearchPage: searching, refresh and failures', () => {
  const periodUrl = `GET /api/venues/search?${new URLSearchParams({
    startDatetime: new Date(start).toISOString(), endDatetime: new Date(end).toISOString() })}`
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC04 (happy): shows that it is searching, then venues without a recorded capacity or facilities', async () => {
    stubApi({ [periodUrl]: () => jsonResponse(200, [{ ...match, venueCapacity: null, venueFacilities: [], setupMinutes: 30, turnaroundMinutes: 15 }]) })
    renderPage()
    enterPeriod()
    fireEvent.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(screen.getByRole('status')).toHaveTextContent('Searching available venues…')
    expect(screen.getByRole('button', { name: 'Searching…' })).toBeDisabled()
    const card = await screen.findByRole('button', { name: '1 Harbour Road' })
    expect(card).toHaveTextContent('Capacity not recorded · No facilities listed')
    fireEvent.click(card)
    expect(within(screen.getByRole('dialog')).getByText('30 / 15 minutes')).toBeInTheDocument()
  })

  it('EC04 (happy): an unticked facility is no longer searched for', async () => {
    const calls = stubApi({ [periodUrl]: () => jsonResponse(200, [match]) })
    renderPage()
    enterPeriod()
    fireEvent.click(screen.getByLabelText('Stage'))
    fireEvent.click(screen.getByLabelText('Stage'))
    fireEvent.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(await screen.findByRole('button', { name: '1 Harbour Road' })).toBeInTheDocument()
    expect(new URL(calls[0].url, 'http://localhost').searchParams.getAll('facility')).toEqual([])
  })

  it('EC04 (failure): a failed search shows the server reason and no results', async () => {
    stubApi({ [periodUrl]: () => jsonResponse(500, { message: 'Venue search is down' }) })
    renderPage()
    enterPeriod()
    fireEvent.click(screen.getByRole('button', { name: 'Search venues' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Venue search is down')
    expect(screen.queryByRole('heading', { name: 'Search results' })).not.toBeInTheDocument()
  })

  it('EC04 (failure): results refresh in the background (closing a vanished venue\'s window), and a failed refresh keeps them with a warning', async () => {
    let answer = () => jsonResponse(200, [match])
    stubApi({ [periodUrl]: () => answer() })
    renderPage()
    enterPeriod()
    fireEvent.click(screen.getByRole('button', { name: 'Search venues' }))
    fireEvent.click(await screen.findByRole('button', { name: '1 Harbour Road' }))
    expect(screen.getByRole('dialog', { name: '1 Harbour Road' })).toBeInTheDocument()

    answer = () => jsonResponse(200, [])
    fireEvent.focus(window)
    expect(await screen.findByText('No matching venues found.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    answer = () => jsonResponse(500, {})
    fireEvent.focus(window)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not refresh venues. Search again to retry.')
    expect(screen.getByText('No matching venues found.')).toBeInTheDocument()
  })
})
