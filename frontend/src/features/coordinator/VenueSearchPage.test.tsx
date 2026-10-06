import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
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
  operatingInformation: 'Daily 08:00-22:00',
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

describe('VenueSearchPage (EC04)', () => {
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

    expect(await screen.findByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText('100 people capacity')).toBeInTheDocument()
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
