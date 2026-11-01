// @stories EO-C2 EO02  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { EventRequestWizardPage } from './EventRequestWizardPage'

const DRAFT_ID = '22222222-2222-2222-2222-222222222222'
const HOUR = 3_600_000
const DAY = 24 * HOUR

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

/** The Singapore wall-clock value ("YYYY-MM-DDTHH:mm") of an instant. */
function singaporeLocal(instant: number): string {
  return new Date(instant + 8 * HOUR).toISOString().slice(0, 16)
}

/** A complete, valid draft starting `startsIn` ms from now, for two hours. */
function completeDraft(startsIn = 7 * DAY) {
  const start = Math.floor((Date.now() + startsIn) / 60_000) * 60_000
  return {
    requestId: DRAFT_ID, requestType: 'C', eventId: null, status: 'draft', organisation: 'Acme Conferences',
    eventName: 'Town Hall', purpose: 'Quarterly update', description: null,
    startDatetime: new Date(start).toISOString(), endDatetime: new Date(start + 2 * HOUR).toISOString(),
    expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null,
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }
}

/** Saves echo what was sent; `submit` decides the submission's answer. */
function stubApi(draft: Record<string, unknown>, submit: () => Response = () => jsonResponse(200, { ...draft, status: 'pending' })) {
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url === '/api/equipment/catalogue') return jsonResponse(200, [])
    if (url === `/api/event-requests/${DRAFT_ID}/submit`) return submit()
    if (url === `/api/event-requests/${DRAFT_ID}` && method === 'GET') return jsonResponse(200, draft)
    if (url === '/api/event-requests' || url === `/api/event-requests/${DRAFT_ID}`) {
      return jsonResponse(method === 'POST' ? 201 : 200, { ...draft, ...JSON.parse(String(init?.body)) })
    }
    throw new Error(`Unexpected fetch: ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function savedBodies(fetch: ReturnType<typeof stubApi>) {
  return fetch.mock.calls
    .filter(([, init]) => init?.method === 'POST' || init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>)
}

function renderDraft() {
  return render(
    <MemoryRouter initialEntries={[`/organiser/requests/${DRAFT_ID}`]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/:requestId" element={<EventRequestWizardPage />} />
          <Route path="/organiser/requests" element={<p>Organiser home</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EO-C2 — a request must describe a real, future event', () => {
  beforeEach(() => {
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'access-1', refreshToken: 'refresh-1', username: 'eo1', role: 'organiser',
      organisation: 'Acme Conferences',
    }))
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  async function toSchedule(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Next' }))
    return {
      start: screen.getByLabelText('Start date & time'),
      end: screen.getByLabelText('End date & time'),
      attendance: screen.getByLabelText('Expected attendance'),
      next: screen.getByRole('button', { name: 'Next' }),
    }
  }

  it('EO-C2 AC5 (happy): dates are entered and shown in Singapore time, labelled, whatever the computer\'s time zone', async () => {
    const draft = completeDraft()
    const fetch = stubApi(draft)
    const user = userEvent.setup()
    renderDraft()
    const { start } = await toSchedule(user)

    // The stored instant is shown as its Singapore wall-clock time.
    expect(start).toHaveValue(singaporeLocal(Date.parse(draft.startDatetime as string)))
    expect(screen.getAllByText('Singapore time (GMT+8)')).toHaveLength(2)

    // 09:00 typed means 09:00 in Singapore: 01:00 UTC.
    const day = singaporeLocal(Date.now() + 10 * DAY).slice(0, 10)
    fireEvent.change(start, { target: { value: `${day}T09:00` } })
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(savedBodies(fetch).at(-1)?.startDatetime).toBe(`${day}T01:00:00.000Z`)
  })

  it('EO-C2 AC2, AC6 (negative): a start in the past is explained next to the start field and blocks Next until corrected', async () => {
    stubApi(completeDraft())
    const user = userEvent.setup()
    renderDraft()
    const { start, next } = await toSchedule(user)

    fireEvent.change(start, { target: { value: singaporeLocal(Date.now() - DAY) } })

    expect(start).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('The start date & time must be in the future.')).toBeInTheDocument()
    expect(next).toBeDisabled()
    fireEvent.change(start, { target: { value: singaporeLocal(Date.now() + 7 * DAY) } })
    expect(screen.queryByText('The start date & time must be in the future.')).not.toBeInTheDocument()
    expect(next).toBeEnabled()
  })

  it('EO-C2 AC4 (boundary): attendance takes whole numbers only; 0 is explained, and a typed or pasted decimal or sign never gets in', async () => {
    stubApi(completeDraft())
    const user = userEvent.setup()
    renderDraft()
    const { attendance, next } = await toSchedule(user)

    await user.clear(attendance)
    await user.type(attendance, '1.5')
    expect(attendance).toHaveValue(15)
    await user.clear(attendance)
    await user.type(attendance, '-3')
    expect(attendance).toHaveValue(3)
    fireEvent.change(attendance, { target: { value: '150.5' } })
    expect(attendance).toHaveValue(3)

    await user.clear(attendance)
    await user.type(attendance, '0')
    expect(screen.getByText('Expected attendance must be at least 1.')).toBeInTheDocument()
    expect(next).toBeDisabled()
    await user.clear(attendance)
    await user.type(attendance, '1')
    expect(next).toBeEnabled()
  })

  it('EO-C2 AC7 (happy): an invalid schedule is still saved as a draft, so nothing typed is lost', async () => {
    const fetch = stubApi(completeDraft())
    const user = userEvent.setup()
    renderDraft()
    const { start, end } = await toSchedule(user)

    fireEvent.change(start, { target: { value: singaporeLocal(Date.now() + 3 * DAY) } })
    fireEvent.change(end, { target: { value: singaporeLocal(Date.now() + 3 * DAY) } })
    await user.click(screen.getByRole('button', { name: 'Back' }))

    const saved = savedBodies(fetch).at(-1)
    expect(saved?.startDatetime).toBe(saved?.endDatetime)
  })

  it('EO-C2 AC6, AC8 (failure): when the server refuses, the organiser is taken to the field with the server\'s message, which clears once they change it', async () => {
    stubApi(completeDraft(), () => jsonResponse(422, {
      message: 'The start date & time must be in the future.',
      fieldErrors: { startDatetime: 'The start date & time must be in the future.' },
    }))
    const user = userEvent.setup()
    renderDraft()
    for (let step = 0; step < 4; step++) await user.click(await screen.findByRole('button', { name: 'Next' }))

    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    const start = await screen.findByLabelText('Start date & time')
    expect(start).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('The start date & time must be in the future.')).toBeInTheDocument()
    fireEvent.change(start, { target: { value: singaporeLocal(Date.now() + 9 * DAY) } })
    expect(screen.queryByText('The start date & time must be in the future.')).not.toBeInTheDocument()
  })

  it('EO-C2 AC2 (conflict): if the start passes while the review step is open, Submit is disabled and the problem links back to the schedule', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(Date.parse('2026-10-23T02:00:00Z'))
    stubApi(completeDraft(30 * 60_000))
    const user = userEvent.setup()
    renderDraft()
    for (let step = 0; step < 4; step++) await user.click(await screen.findByRole('button', { name: 'Next' }))
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
    expect(screen.getAllByText(/\(Singapore time\)/, { selector: 'dd' })).toHaveLength(2)

    // An hour passes: the event has now started.
    vi.setSystemTime(Date.parse('2026-10-23T03:00:00Z'))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(screen.getByRole('alert')).toHaveTextContent('The start date & time must be in the future.')
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Fix schedule & attendance' }))
    expect(screen.getByLabelText('Start date & time')).toHaveAttribute('aria-invalid', 'true')
  })
})
