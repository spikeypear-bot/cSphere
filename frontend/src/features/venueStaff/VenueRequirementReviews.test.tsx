// @stories VS17
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { VenueRequirementReviews } from './VenueRequirementReviews'
import type { VenueRequirementReviewView } from '../../types/venueRequirementReview'

function view(id = 'r1', bookingId = 'b1'): VenueRequirementReviewView {
  return { review: { reviewId: id, bookingId, changeRequestId: 'c1', eventId: 'e1', eventName: 'Conference',
    venueAddress: bookingId === 'b1' ? 'Main Hall' : 'Second Hall', approvedAt: '2027-03-01T01:00:00Z',
    coordinatorName: 'ec1', coordinatorEmail: 'ec1@example.test', reservedStart: '2027-03-10T01:00:00Z', reservedEnd: '2027-03-10T04:00:00Z',
    setupDurationMinutes: 30, turnaroundDurationMinutes: 45, status: 'pending', reviewedAt: null, reviewerName: null, reviewNote: null, reviewedChecks: null },
    changes: [{ field: 'expectedAttendance', label: 'Expected attendance', before: 100, after: 200 },
      { field: 'venueRequirements', label: 'Venue requirements', before: 'Classroom', after: 'Theatre' }],
    checks: [{ key: 'capacity', status: 'ok', title: 'Check capacity', detail: '200 guests fit.' }],
    reservedStart: '2027-03-10T01:00:00Z', reservedEnd: '2027-03-10T04:00:00Z', setupDurationMinutes: 30, turnaroundDurationMinutes: 45, canReview: true, suitability: {
      checks: [{ key: 'capacity', status: 'ok', title: 'Capacity', detail: 'Fits 200 attendees (capacity 300)' },
        { key: 'clash', status: 'ok', title: 'Booking conflicts', detail: 'None' },
        { key: 'hold', status: 'ok', title: 'Tentative holds', detail: 'No conflicts' }],
      venueRequirements: 'Theatre', supportedLayouts: ['theatre', 'classroom'],
      occupiedStart: '2027-03-10T00:30:00Z', occupiedEnd: '2027-03-10T04:45:00Z' } }
}
function json(body: unknown, status = 200): Response { return { ok: status < 400, status, text: async () => JSON.stringify(body) } as Response }
function decided(v: VenueRequirementReviewView, outcome: 'suitable' | 'action_required' = 'suitable', note: string | null = null) {
  return { ...v, canReview: false, review: { ...v.review, status: outcome, reviewerName: 'vs1', reviewedAt: '2027-03-02T01:00:00Z', reviewNote: note, reviewedChecks: v.checks } }
}
beforeEach(() => { localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh', userId: 'vs1', username: 'vs1', role: 'venue-staff', organisation: 'ConnectSphere' })) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })

describe('VS17 booking requirement review workflow', () => {
  it('VS17 (happy): shows historical requirements, actual reservations and an independent Review Required indicator', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([view()])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(screen.getByText('Loading requirement reviews…')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Main Hall — Review Required' })).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument(); expect(screen.getByText('200')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Approved Requirement Changes' })).getByText('Theatre')).toBeInTheDocument()
    expect(screen.queryByText(/notification read status does not complete a review/)).not.toBeInTheDocument()
    expect(screen.getByText('Actual reserved times').closest('dl')).toHaveTextContent('30 / 45 minutes')
    expect(screen.getByLabelText('Booking ID')).toHaveClass('venue-card-preview__id')
    expect(screen.getByRole('table', { name: 'Venue suitability results' })).toHaveTextContent('Fits 200 attendees')
    expect(screen.getByText('No issues detected')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Approved Requirement Changes' })).toHaveTextContent('Theatre, Classroom')
    expect(screen.getByText('Staff assessment needed for written requirements and layout.')).toBeInTheDocument()
    expect(screen.queryByText(/Checked against current approved requirements/)).not.toBeInTheDocument()
  })
  it('VS17 (happy): records suitable without changing booking state and replaces the pending indicator with the audited outcome', async () => {
    const v = view()
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => json(init?.method === 'POST' ? decided(v) : [v]))
    vi.stubGlobal('fetch', fetcher)
    render(<VenueRequirementReviews bookingId="b1" />)
    await userEvent.selectOptions(await screen.findByLabelText('Outcome'), 'suitable')
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(await screen.findByRole('heading', { name: 'Main Hall — Remains suitable' })).toBeInTheDocument()
    expect(screen.queryByText('Review Required')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save review' })).not.toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledWith('/api/venue-bookings/b1/requirement-reviews/r1/decision', expect.objectContaining({ method: 'POST', body: JSON.stringify({ outcome: 'suitable', note: null }) }))
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
  it('VS17 (negative): requires a comment for coordinator action and sends that comment with the outcome', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => json(init?.method === 'POST' ? decided(view(), 'action_required', 'Find a larger hall') : [view()]))
    vi.stubGlobal('fetch', fetcher)
    render(<VenueRequirementReviews bookingId="b1" />)
    await userEvent.selectOptions(await screen.findByLabelText('Outcome'), 'action_required')
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Explain what the coordinator needs to do.')
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    await userEvent.type(screen.getByLabelText('Review comment (required)'), 'Find a larger hall')
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(await screen.findByRole('heading', { name: 'Main Hall — Coordinator action required' })).toBeInTheDocument()
    expect(screen.getByText('Find a larger hall')).toBeInTheDocument()
  })
  it('VS17 (failure): failed reads show a retry control and the successful retry restores the review', async () => {
    let calls = 0
    vi.stubGlobal('fetch', vi.fn(async () => ++calls === 1 ? json({ message: 'Temporarily unavailable' }, 503) : json([view()])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load requirement reviews')
    await userEvent.click(screen.getByRole('button', { name: 'Retry requirement reviews' }))
    expect(await screen.findByRole('heading', { name: 'Main Hall — Review Required' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  it('VS17 (failure): failed saves retain staff input and can be retried without clearing Review Required', async () => {
    let writes = 0
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method !== 'POST' ? json([view()])
      : ++writes === 1 ? json({ message: 'Storage unavailable. Try again.' }, 503) : json(decided(view(), 'action_required', 'Check access'))))
    render(<VenueRequirementReviews bookingId="b1" />)
    await userEvent.selectOptions(await screen.findByLabelText('Outcome'), 'action_required')
    await userEvent.type(screen.getByLabelText('Review comment (required)'), 'Check access')
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Storage unavailable')
    expect(screen.getByLabelText('Review comment (required)')).toHaveValue('Check access')
    expect(screen.getByRole('heading', { name: 'Main Hall — Review Required' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(await screen.findByRole('heading', { name: 'Main Hall — Coordinator action required' })).toBeInTheDocument()
  })
  it('VS17 (happy): coordinators see outcomes and pending reviews for separate venues without staff actions', async () => {
    const first = decided(view(), 'action_required', 'Find a larger hall')
    vi.stubGlobal('fetch', vi.fn(async () => json([first, { ...view('r2', 'b2'), canReview: false }])))
    render(<VenueRequirementReviews eventId="e1" />)
    expect(await screen.findByRole('heading', { name: 'Main Hall — Coordinator action required' })).toBeInTheDocument()
    expect(screen.getByText('Find a larger hall')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Second Hall — Review Required' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save review' })).not.toBeInTheDocument()
  })
  it('VS17 (negative): access denial does not expose a form or previous booking history', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: 'Access denied' }, 403)))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.queryByLabelText('Outcome')).not.toBeInTheDocument()
  })
  it('VS17 (conflict): another staff decision refreshes history instead of overwriting it', async () => {
    let wrote = false
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') { wrote = true; return json({ message: 'Already recorded. Refresh.' }, 409) }
      return json([wrote ? decided(view()) : view()])
    }))
    render(<VenueRequirementReviews bookingId="b1" />)
    await userEvent.selectOptions(await screen.findByLabelText('Outcome'), 'suitable')
    await userEvent.click(screen.getByRole('button', { name: 'Save review' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Main Hall — Remains suitable' })).toBeInTheDocument())
    expect(screen.queryByLabelText('Outcome')).not.toBeInTheDocument()
  })
  it('VS17 (negative): highlights failed checks and does not present unavailable summary data as a successful check', async () => {
    const v = view()
    v.suitability!.checks[0] = { key: 'capacity', status: 'attention', title: 'Capacity', detail: '200 attendees exceed capacity 100' }
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    const rendered = render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByText('Requires attention')).toBeInTheDocument()
    expect(screen.getByText('200 attendees exceed capacity 100').closest('tr')).toHaveAttribute('data-attention', 'true')
    rendered.unmount()
    delete v.suitability
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByText('Suitability summary unavailable.')).toBeInTheDocument()
    expect(screen.queryByText('No issues detected')).not.toBeInTheDocument()
  })
  it('VS17 (boundary): unchanged reservation details appear once, while different historical times and durations are retained', async () => {
    const v = view()
    v.review.reservedStart = '2027-03-10T09:00:00+08:00'
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    const page = render(<VenueRequirementReviews bookingId="b1" />)
    await screen.findByRole('heading', { name: 'Main Hall — Review Required' })
    expect(screen.queryByText('Reserved when the change was approved')).not.toBeInTheDocument()
    expect(screen.queryByText('Setup / Turnaround at approval')).not.toBeInTheDocument()
    expect(screen.getAllByText('Setup / Turnaround')).toHaveLength(1)
    expect(screen.getAllByText('Review Required', { exact: true })).toHaveLength(1)
    const changeTable = screen.getByRole('table', { name: 'Approved Requirement Changes' })
    expect(within(changeTable).getByRole('columnheader', { name: 'New' })).toHaveClass('venue-reviews__new')
    expect(within(changeTable).getByText('200').closest('td')).toHaveClass('venue-reviews__new')
    expect(within(changeTable).getByText('200').tagName).toBe('STRONG')
    page.unmount()
    v.review.reservedStart = '2027-03-10T08:00:00+08:00'
    v.review.setupDurationMinutes = 10
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByText('Reserved when the change was approved')).toBeInTheDocument()
    expect(screen.getByText('Setup / Turnaround at approval').nextElementSibling).toHaveTextContent('10 / 45 minutes')
    expect(screen.getByText('Actual reserved times')).toBeInTheDocument()
  })
  it('VS17 (boundary): unchanged venue text is omitted and the occupied period follows setup and turnaround', async () => {
    const v = view()
    v.changes = v.changes.filter(c => c.field !== 'venueRequirements')
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    await screen.findByRole('heading', { name: 'Main Hall — Review Required' })
    expect(screen.queryByText(/Venue supports:/)).not.toBeInTheDocument()
    expect(screen.queryByText('Staff assessment needed for written requirements and layout.')).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Venue requirement and supported layouts' })).not.toBeInTheDocument()
    const setupRow = screen.getByText('Setup / Turnaround').parentElement!
    expect(setupRow.nextElementSibling).toHaveTextContent('Occupied period')
    expect(setupRow.nextElementSibling).toHaveTextContent('Includes setup and turnaround.')
    expect(screen.getAllByText('Occupied period')).toHaveLength(1)
  })
})
