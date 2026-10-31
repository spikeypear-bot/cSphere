// @stories DEV16 VS17
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { VenueRequirementReviews } from './VenueRequirementReviews'
import type { VenueRequirementReviewView } from '../../types/venueRequirementReview'
import { apiClient } from '../../lib/apiClient'

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
describe('VS17 audit review currency', () => {
  it('VS17 (conflict): superseded pending reviews retain history and hide decision forms even when an old response says actionable', async () => {
    const old = { ...view(), outcomeCurrent: false, canReview: true, currentCoordinatorName: 'ec2', currentCoordinatorEmail: 'ec2@example.test' }
    const fetcher = vi.fn(async () => json([old]))
    vi.stubGlobal('fetch', fetcher)
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByRole('heading', { name: 'Main Hall — Previous review' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save review' })).not.toBeInTheDocument()
    expect(screen.getByText(/This review has been superseded/)).toBeInTheDocument()
    expect(screen.getByText(/Current coordinator: ec2/)).toBeInTheDocument()
    expect(screen.getByText(/Coordinator at approval: ec1/)).toBeInTheDocument()
    expect(fetcher.mock.calls).toHaveLength(1)
  })
})
function decided(v: VenueRequirementReviewView, outcome: 'suitable' | 'action_required' = 'suitable', note: string | null = null) {
  return { ...v, canReview: false, review: { ...v.review, status: outcome, reviewerName: 'vs1', reviewedAt: '2027-03-02T01:00:00Z', reviewNote: note, reviewedChecks: v.checks } }
}
beforeEach(() => { localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh', userId: 'vs1', username: 'vs1', role: 'venue-staff', organisation: 'ConnectSphere' })) })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })

describe('DEV16 review display and recovery coverage', () => {
  it('DEV16 (negative): a review without an outcome cannot be submitted', async () => {
    const fetch = vi.fn(async () => json([view()]))
    vi.stubGlobal('fetch', fetch)
    render(<VenueRequirementReviews bookingId="b1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Save review' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a review outcome.')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it.each(['attention', 'cancelled'])('DEV16 (boundary): inactive pending reviews explain why staff cannot decide (%s)', async bookingStatus => {
    const v = { ...view(), bookingStatus, canReview: false }
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByText(bookingStatus === 'attention'
      ? 'Resolve the booking suitability issues before recording this review. Its history is retained.'
      : 'This booking is no longer active. Its review history is retained.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save review' })).not.toBeInTheDocument()
  })
  it('DEV16 (conflict): an invalidated completed review keeps its previous decision and current attention label', async () => {
    const v: VenueRequirementReviewView = { ...decided(view()), bookingStatus: 'attention' }
    v.review.reviewedAt = null
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByRole('heading', { name: 'Main Hall — Requires attention' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Previous decision: Remains suitable.')
    expect(screen.queryByRole('button', { name: 'Save review' })).not.toBeInTheDocument()
  })
  it('DEV16 (conflict): an actionable review with failed checks requires coordinator action', async () => {
    const v = view()
    v.checks = [{ key: 'capacity', title: 'Capacity', status: 'attention', detail: 'Capacity exceeded' }]
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    expect(await screen.findByText('Current checks require attention. Record coordinator action required, or refresh after arrangements are resolved.')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Remains suitable' })).toBeDisabled()
    expect(screen.getByRole('option', { name: 'Coordinator action required' })).toBeEnabled()
  })
  it('DEV16 (boundary): history displays absent, dated, list and legacy requirement values without dropping information', async () => {
    const v = view()
    v.review.coordinatorName = null; v.review.coordinatorEmail = null
    v.reservedEnd = '2027-03-10T05:00:00Z'; v.turnaroundDurationMinutes = 60
    v.suitability!.supportedLayouts = []
    v.changes = [
      { field: 'startDatetime', label: 'Start', before: '2027-03-10T01:00:00Z', after: '2027-03-10T02:00:00Z' },
      { field: 'accessibilityNeeds', label: 'Accessibility', before: ['step_free_access', 'legacy_access'], after: [] },
      { field: 'requiredFacilities', label: 'Facilities', before: ['projection', 'legacy_facility'], after: [] },
      { field: 'legacyList', label: 'Legacy values', before: [123], after: [] },
      { field: 'venueRequirements', label: 'Written requirements', before: null, after: '' },
    ]
    vi.stubGlobal('fetch', vi.fn(async () => json([v])))
    render(<VenueRequirementReviews bookingId="b1" />)
    await screen.findByRole('heading', { name: 'Main Hall — Review Required' })
    expect(screen.getByText('Step-free access, legacy_access')).toBeInTheDocument()
    expect(screen.getByText('Projection, legacy_facility')).toBeInTheDocument()
    expect(screen.getByText('123')).toBeInTheDocument()
    expect(screen.getAllByText('None specified').length).toBeGreaterThanOrEqual(4)
    expect(screen.getByText('Venue supports: Not recorded')).toBeInTheDocument()
    expect(screen.getByText('Reserved when the change was approved')).toBeInTheDocument()
    expect(screen.getByText('Setup / Turnaround at approval')).toBeInTheDocument()
    expect(screen.getByText('Coordinator at approval: Unassigned', { exact: false })).toBeInTheDocument()
  })
  it('DEV16 (negative): comments over the limit are refused before a write, and unexpected save errors retain the decision', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([view()])))
    const post = vi.spyOn(apiClient, 'post').mockRejectedValue(new Error('Offline'))
    render(<VenueRequirementReviews bookingId="b1" />)
    await screen.findByLabelText('Outcome')
    fireEvent.change(screen.getByLabelText('Outcome'), { target: { value: 'suitable' } })
    fireEvent.change(screen.getByLabelText('Review comment (optional)'), { target: { value: 'x'.repeat(2001) } })
    fireEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(screen.getByRole('alert')).toHaveTextContent('at most 2000 characters')
    expect(post).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Review comment (optional)'), { target: { value: 'Still suitable' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save review' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The review could not be saved. Please try again.')
    expect(screen.getByLabelText('Outcome')).toHaveValue('suitable')
    expect(screen.getByLabelText('Review comment (optional)')).toHaveValue('Still suitable')
  })
  it('DEV16 (happy): saving one review preserves its sibling review and labels unmapped supported layouts', async () => {
    const one = view(), two = view('r2', 'b2')
    one.suitability!.supportedLayouts = ['legacy_layout']
    const saved = decided(one)
    vi.stubGlobal('fetch', vi.fn(async (_: unknown, init?: RequestInit) => json(init?.method === 'POST' ? saved : [one, two])))
    render(<VenueRequirementReviews bookingId="b1" />)
    const first = await screen.findByRole('article', { name: 'Requirement review for Main Hall' })
    expect(within(first).getByText('Venue supports: legacy_layout')).toBeInTheDocument()
    fireEvent.change(within(first).getByLabelText('Outcome'), { target: { value: 'suitable' } })
    fireEvent.click(within(first).getByRole('button', { name: 'Save review' }))
    expect(await within(first).findByText('Reviewed by vs1')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Requirement review for Second Hall' })).toHaveTextContent('Review Required')
  })
})

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
    expect(screen.getByText('Review the requested room layout and any written requirements.')).toBeInTheDocument()
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
    expect(screen.queryByText('Review the requested room layout and any written requirements.')).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Venue requirement and supported layouts' })).not.toBeInTheDocument()
    const setupRow = screen.getByText('Setup / Turnaround').parentElement!
    expect(setupRow.nextElementSibling).toHaveTextContent('Occupied period')
    expect(setupRow.nextElementSibling).toHaveTextContent('Includes setup and turnaround.')
    expect(screen.getAllByText('Occupied period')).toHaveLength(1)
  })
})

it.each([null, {}, [{unexpected:'response'}]])('VS17 (failure): malformed review responses show retry and recover without crashing the booking: %j',async invalid=>{
 const fetcher=vi.fn().mockResolvedValueOnce(json(invalid)).mockResolvedValueOnce(json([]))
 vi.stubGlobal('fetch',fetcher);render(<VenueRequirementReviews bookingId="b1"/>)
 expect(await screen.findByRole('alert')).toHaveTextContent('Could not load requirement reviews.')
 expect(screen.queryByText('No approved requirement changes need review for this booking.')).not.toBeInTheDocument()
 await userEvent.click(screen.getByRole('button',{name:'Retry requirement reviews'}))
 await waitFor(()=>expect(screen.queryByRole('region',{name:'Venue requirement reviews'})).not.toBeInTheDocument())
 expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it.each([{bookingId:'b1'},{eventId:'e1'}])('VS17 (boundary): an empty successful review list hides the whole section: %j',async props=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json([])))
 render(<VenueRequirementReviews {...props}/>)
 expect(screen.getByText('Loading requirement reviews\u2026')).toBeInTheDocument()
 await waitFor(()=>expect(screen.queryByRole('region',{name:'Venue requirement reviews'})).not.toBeInTheDocument())
 expect(screen.queryByRole('heading',{name:'Venue Requirement Reviews'})).not.toBeInTheDocument()
})
it('VS17 (failure): a refresh failure after an empty list shows retry instead of hiding the error',async()=>{
 let failed=false
 vi.stubGlobal('fetch',vi.fn(async()=>failed?json({message:'Offline'},503):json([])))
 render(<VenueRequirementReviews bookingId="b1"/>)
 await waitFor(()=>expect(screen.queryByRole('region',{name:'Venue requirement reviews'})).not.toBeInTheDocument())
 failed=true;fireEvent.focus(window)
 expect(await screen.findByRole('alert')).toHaveTextContent('Could not load requirement reviews.')
 failed=false;await userEvent.click(screen.getByRole('button',{name:'Retry requirement reviews'}))
 await waitFor(()=>expect(screen.queryByRole('region',{name:'Venue requirement reviews'})).not.toBeInTheDocument())
})
