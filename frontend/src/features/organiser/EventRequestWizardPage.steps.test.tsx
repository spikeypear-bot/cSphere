// @stories EO01 EO02 EO-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { formatSingaporeDateTime } from '../../lib/singaporeTime'
import { jsonResponse, seedSession, stubApi, type Call } from '../../test/apiStubs'
import { EventRequestWizardPage } from './EventRequestWizardPage'

const ID = '33333333-3333-3333-3333-333333333333'
const NOW = Date.parse('2026-10-23T02:00:00Z')
// 30 days ahead, 10:00-12:00 Singapore time.
const START = '2026-11-22T02:00:00.000Z'
const END = '2026-11-22T04:00:00.000Z'
const CATALOGUE = [
  { equipmentId: 'mic-id', equipmentName: 'Microphone', totalQuantity: 10, serialised: false },
  { equipmentId: 'projector-id', equipmentName: 'Projector', totalQuantity: 6, serialised: true },
]

function draft(overrides: Record<string, unknown> = {}) {
  return {
    requestId: ID, status: 'draft', organisation: 'Acme Pte Ltd',
    eventName: 'Town Hall', purpose: 'Quarterly update', description: null,
    startDatetime: START, endDatetime: END, expectedAttendance: 150,
    venueRequirements: 'Theatre seating', equipmentRequirements: null,
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false,
    createdAt: new Date(NOW).toISOString(), updatedAt: new Date(NOW).toISOString(),
    ...overrides,
  }
}

/** Saves echo what was sent; `extra` adds or replaces routes. */
function stubDraft(saved: Record<string, unknown>, extra: Record<string, (body: unknown) => Response> = {}) {
  return stubApi({
    [`GET /api/event-requests/${ID}`]: () => jsonResponse(200, saved),
    [`PUT /api/event-requests/${ID}`]: (body) => jsonResponse(200, { ...saved, ...(body as object) }),
    'POST /api/event-requests': (body) => jsonResponse(201, { ...saved, ...(body as object) }),
    'GET /api/equipment/catalogue': () => jsonResponse(200, CATALOGUE),
    ...extra,
  })
}

function lastSave(calls: Call[]) {
  return calls.filter((c) => c.method === 'PUT' || (c.method === 'POST' && c.url === '/api/event-requests'))
    .at(-1)?.body as Record<string, unknown>
}

function RequestList() {
  const { state } = useLocation()
  return <p>Organiser home{state?.justSubmitted ? ' (just submitted)' : ''}</p>
}

function renderWizard(path = `/organiser/requests/${ID}`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/:requestId" element={<EventRequestWizardPage />} />
          <Route path="/organiser/requests/new" element={<EventRequestWizardPage />} />
          <Route path="/organiser/requests" element={<RequestList />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function next(user: ReturnType<typeof userEvent.setup>, times = 1) {
  for (let i = 0; i < times; i++) await user.click(await screen.findByRole('button', { name: 'Next' }))
}

describe('EO01 EO02 — every wizard step, what it saves and how it is reviewed', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    seedSession('organiser')
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    window.localStorage.clear()
  })

  it('EO01 (happy): basics and venue details typed, ticked and cleared are what "Save as draft" and "Exit" save', async () => {
    const calls = stubDraft(draft({ eventName: null, purpose: null, venueRequirements: null }))
    const user = userEvent.setup()
    renderWizard()

    await user.type(await screen.findByLabelText('Event name'), 'Alumni Night')
    await user.type(screen.getByLabelText('Purpose'), 'Reconnect')
    await user.type(screen.getByLabelText('Description'), 'Dinner')
    await user.click(screen.getByLabelText('Stage'))
    await user.click(screen.getByLabelText('Projection'))
    await user.click(screen.getByLabelText('Stage'))
    await user.click(screen.getByRole('button', { name: 'Save as draft' }))
    await waitFor(() => expect(lastSave(calls)).toMatchObject({
      eventName: 'Alumni Night', purpose: 'Reconnect', description: 'Dinner', requiredFacilities: ['projection'],
    }))

    await next(user, 2)
    await user.type(screen.getByLabelText('Venue requirements'), 'Banquet')
    await next(user)
    expect(lastSave(calls)).toMatchObject({ venueRequirements: 'Banquet' })
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await user.clear(await screen.findByLabelText('Venue requirements'))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(lastSave(calls)).toMatchObject({ venueRequirements: null })

    // A field emptied again is saved as "not provided", not as an empty string.
    await user.clear(screen.getByLabelText('Purpose'))
    await user.clear(screen.getByLabelText('Description'))
    await user.clear(screen.getByLabelText('Event name'))
    await user.click(screen.getByRole('button', { name: 'Exit' }))
    expect(await screen.findByText('Organiser home')).toBeInTheDocument()
    expect(lastSave(calls)).toMatchObject({ eventName: null, purpose: null, description: null })
  })

  it('EO01 (happy): a reopened draft keeps its chosen equipment and quantities, and removing one or adding notes is saved', async () => {
    const calls = stubDraft(draft({
      equipmentRequirements: 'Equipment items:\n3 × Projector',
      equipmentItems: [{ equipmentId: 'projector-id', quantity: 3 }],
    }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    const projector = await screen.findByLabelText('Projector')
    await waitFor(() => expect(projector).toBeChecked())
    expect(screen.getByRole('spinbutton', { name: 'Projector quantity' })).toHaveValue(3)
    expect(screen.getByLabelText('Microphone')).not.toBeChecked()

    await user.type(screen.getByLabelText('Additional equipment notes'), 'Stage left')
    await user.click(screen.getByLabelText('Projector'))
    await next(user)

    expect(lastSave(calls)).toMatchObject({
      equipmentItems: [], equipmentRequirements: 'Additional notes:\nStage left',
    })
    expect(screen.getByText('Additional notes: Stage left', { normalizer: (s) => s.replace(/\s+/g, ' ').trim() })).toBeInTheDocument()
  })

  it('EO01 (failure): when the equipment list cannot load the organiser is told why and can try again', async () => {
    let attempts = 0
    stubDraft(draft(), {
      'GET /api/equipment/catalogue': () => (++attempts === 1
        ? jsonResponse(503, { message: 'Equipment service unavailable' })
        : jsonResponse(200, CATALOGUE)),
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    expect(await screen.findByRole('alert')).toHaveTextContent('Equipment service unavailable')
    expect(screen.queryByLabelText('Projector')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByLabelText('Projector')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('EO01 (failure): an unreadable equipment answer shows a general message instead of a broken list', async () => {
    stubDraft(draft(), {
      'GET /api/equipment/catalogue': () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response,
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load equipment options.')
  })

  it('EO01 (boundary): leaving the equipment step before the list arrives loads it afresh on return', async () => {
    let answerFirst: (r: Response) => void = () => {}
    let attempts = 0
    stubDraft(draft(), {
      'GET /api/equipment/catalogue': () => (++attempts === 1
        ? new Promise<Response>((resolve) => { answerFirst = resolve }) as unknown as Response
        : jsonResponse(200, CATALOGUE)),
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)
    expect(await screen.findByText('Loading equipment options…')).toBeInTheDocument()

    await next(user)
    answerFirst(jsonResponse(200, []))
    await user.click(await screen.findByRole('button', { name: 'Back' }))

    expect(await screen.findByLabelText('Projector')).toBeInTheDocument()
    expect(attempts).toBe(2)
  })

  it('EO-C2 (negative): a selected item with no quantity keeps the organiser on the step and takes them to it', async () => {
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    stubDraft(draft())
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)
    await user.click(await screen.findByLabelText('Microphone'))
    const quantity = screen.getByRole('spinbutton', { name: 'Microphone quantity' })
    await user.clear(quantity)

    expect(screen.getByText('Enter a whole quantity of at least 1 for each selected item.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(quantity).toHaveFocus())
    expect(scrolled).toHaveBeenCalled()
    expect(screen.getByLabelText('Additional equipment notes')).toBeInTheDocument()
    delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
  })

  it('EO02 (happy): registration is pre-filled from the schedule, the closing time can be chosen and reset, and it can be turned off', async () => {
    const calls = stubDraft(draft({ registrationNeeds: true }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    // Opens a week before the start, capacity matches the expected attendance.
    expect(await screen.findByLabelText('Registration capacity')).toHaveValue(150)
    expect(screen.getByLabelText('Registration opens')).toHaveValue('2026-11-15T10:00')
    const closes = `when the event starts (${formatSingaporeDateTime(START)}, Singapore time)`
    expect(screen.getByText(closes)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Choose a different time' }))
    fireEvent.change(screen.getByLabelText('Registration closes (optional)'), { target: { value: '2026-11-21T18:00' } })
    expect(screen.getByText(formatSingaporeDateTime('2026-11-21T10:00:00Z'))).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close at event start instead' }))
    expect(screen.queryByLabelText('Registration closes (optional)')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Registration capacity'), { target: { value: '120' } })
    fireEvent.change(screen.getByLabelText('Registration opens'), { target: { value: '2026-11-10T09:00' } })
    await user.type(screen.getByLabelText('Attendee instructions'), 'Bring ID')
    await user.clear(screen.getByLabelText('Attendee instructions'))
    await user.type(screen.getByLabelText('Attendee instructions'), 'Bring ID')
    await next(user)
    expect(lastSave(calls)).toMatchObject({
      registrationNeeds: true, registrationCapacity: 120, registrationOpensAt: '2026-11-10T01:00:00.000Z',
      registrationClosesAt: null, attendeeInstructions: 'Bring ID',
    })

    await user.click(screen.getByRole('button', { name: 'Back' }))
    await user.click(screen.getByLabelText('Attendees need to register for this event'))
    expect(screen.queryByLabelText('Registration capacity')).not.toBeInTheDocument()
    await next(user)
    expect(screen.getByText('No registration required')).toBeInTheDocument()
    expect(lastSave(calls)).toMatchObject({ registrationNeeds: false })
  })

  it('EO02 (boundary): with no schedule yet, registration simply closes when the event starts and nothing is guessed', async () => {
    stubDraft(draft({ startDatetime: null, endDatetime: null, expectedAttendance: null, registrationNeeds: true }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    expect(await screen.findByText('when the event starts')).toBeInTheDocument()
    expect(screen.getByText(/Registration closes when the event starts unless you choose a closing time\./)).toBeInTheDocument()
    expect(screen.getByLabelText('Registration capacity')).toHaveValue(null)
    expect(screen.getByLabelText('Registration opens')).toHaveValue('')
  })

  it('EO-C2 (negative): registration opening after the event start keeps the organiser on the step with the reason', async () => {
    stubDraft(draft({ registrationNeeds: true, registrationCapacity: 100, registrationOpensAt: '2026-11-23T02:00:00Z' }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)
    const opens = await screen.findByLabelText('Registration opens')
    expect(screen.getByText('Registration must open before the event start.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(opens).toHaveFocus())
    expect(screen.queryByRole('button', { name: 'Submit for review' })).not.toBeInTheDocument()
  })

  it('EO02 (happy): submitting shows it is in progress, then returns to the list with the submitted confirmation', async () => {
    let answer: (r: Response) => void = () => {}
    stubDraft(draft(), {
      [`POST /api/event-requests/${ID}/submit`]: () =>
        new Promise<Response>((resolve) => { answer = resolve }) as unknown as Response,
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 4)
    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    expect(await screen.findByRole('button', { name: 'Submitting…' })).toBeDisabled()
    answer(jsonResponse(200, draft({ status: 'pending' })))
    expect(await screen.findByText('Organiser home (just submitted)')).toBeInTheDocument()
  })

  it('EO02 (failure): the server refusing the registration settings takes the organiser back to them with its reason', async () => {
    stubDraft(draft({ registrationNeeds: true, registrationCapacity: 900, registrationOpensAt: '2026-11-01T02:00:00Z' }), {
      [`POST /api/event-requests/${ID}/submit`]: () => jsonResponse(422, {
        message: 'Invalid registration', fieldErrors: { registrationCapacity: 'Registration capacity cannot exceed 500.' },
      }),
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 4)
    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    expect(await screen.findByText('Registration capacity cannot exceed 500.')).toBeInTheDocument()
    expect(screen.getByLabelText('Registration capacity')).toHaveAttribute('aria-invalid', 'true')
  })

  it('EO02 (failure): when ConnectSphere cannot be reached on submit the organiser stays on the review with the reason and can retry', async () => {
    stubDraft(draft(), {
      [`POST /api/event-requests/${ID}/submit`]: () => { throw new Error('offline') },
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 4)
    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach ConnectSphere. Check your connection and try again.')
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  })

  it('EO02 (boundary): a missing field the wizard has no label for is still listed, by its name', async () => {
    stubDraft(draft(), {
      [`POST /api/event-requests/${ID}/submit`]: () => jsonResponse(422, {
        message: 'Missing', missingFields: ['organisationContact'],
      }),
    })
    const user = userEvent.setup()
    renderWizard()
    await next(user, 4)
    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    const missing = await screen.findByText('This request still needs:')
    expect(within(missing.parentElement as HTMLElement).getByText('organisationContact')).toBeInTheDocument()
  })

  it('EO02 (happy): the review lists every answer, including registration, and each Edit opens the step that owns it', async () => {
    stubDraft(draft({
      requiredFacilities: ['stage', 'projection'], registrationNeeds: true, registrationCapacity: 120,
      registrationOpensAt: '2026-11-15T02:00:00Z', attendeeInstructions: 'Bring ID',
      equipmentRequirements: 'Additional notes:\nStage left',
    }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 4)

    const value = (label: string) => screen.getByText(label, { selector: 'dt' }).nextElementSibling
    expect(value('Required facilities')).toHaveTextContent('Stage, Projection')
    expect(value('Registration capacity')).toHaveTextContent('120')
    expect(value('Registration opens')).toHaveTextContent(`${formatSingaporeDateTime('2026-11-15T02:00:00Z')} (Singapore time)`)
    // No closing time chosen: it closes at the event start.
    expect(value('Registration closes')).toHaveTextContent(`${formatSingaporeDateTime(START)} (Singapore time)`)
    expect(value('Attendee instructions')).toHaveTextContent('Bring ID')

    const owners: [string, string][] = [
      ['Event name', 'Event name'], ['Purpose', 'Purpose'],
      ['Start', 'Start date & time'], ['End', 'Start date & time'], ['Expected attendance', 'Expected attendance'],
      ['Venue requirements', 'Venue requirements'], ['Required facilities', 'Venue requirements'],
      ['Accessibility needs', 'Venue requirements'],
      ['Equipment requirements', 'Additional equipment notes'], ['Registration needs', 'Registration capacity'],
      ['Registration capacity', 'Registration capacity'], ['Registration opens', 'Registration capacity'],
      ['Registration closes', 'Registration capacity'], ['Attendee instructions', 'Registration capacity'],
    ]
    const stepOf: Record<string, number> = {
      'Event name': 0, Purpose: 0, 'Start date & time': 1, 'Expected attendance': 1,
      'Venue requirements': 2, 'Additional equipment notes': 3, 'Registration capacity': 3,
    }
    for (const [row, field] of owners) {
      const summary = screen.getByText(row, { selector: 'dt' }).parentElement as HTMLElement
      await user.click(within(summary).getByRole('button', { name: 'Edit' }))
      expect(await screen.findByLabelText(field)).toBeInTheDocument()
      await next(user, 4 - stepOf[field])
    }
  }, 30_000)

  it('EO01 (happy): a reopened draft keeps the registration closing time the organiser chose', async () => {
    stubDraft(draft({
      registrationNeeds: true, registrationCapacity: 100, registrationOpensAt: '2026-11-01T02:00:00Z',
      registrationClosesAt: '2026-11-21T10:00:00Z',
    }))
    const user = userEvent.setup()
    renderWizard()
    await next(user, 3)

    expect(await screen.findByLabelText('Registration closes (optional)')).toHaveValue('2026-11-21T18:00')
  })

  it('EO01 (failure): a draft whose answer cannot be read shows a general load error, not an empty form', async () => {
    stubApi({ [`GET /api/event-requests/${ID}`]: () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response })
    renderWizard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load this request.')
    expect(screen.queryByLabelText('Event name')).not.toBeInTheDocument()
  })

  it('EO01 (boundary): an unreadable local backup is ignored and a new request starts blank', async () => {
    window.localStorage.setItem('connectsphere.draft-backup.Acme Pte Ltd.new', '{not json')
    stubDraft(draft())
    renderWizard('/organiser/requests/new')

    expect(await screen.findByLabelText('Event name')).toHaveValue('')
    expect(screen.getByText('0% ready to submit')).toBeInTheDocument()
  })

  it('EO01 (conflict): pressing "Save as draft" again while a save is still on its way sends only one save', async () => {
    let answer: (r: Response) => void = () => {}
    const calls = stubDraft(draft(), {
      'POST /api/event-requests': (body) => new Promise<Response>((resolve) => {
        answer = () => resolve(jsonResponse(201, { ...draft(), ...(body as object) }))
      }) as unknown as Response,
    })
    const user = userEvent.setup()
    renderWizard('/organiser/requests/new')
    await user.type(screen.getByLabelText('Event name'), 'Gala')
    await user.click(screen.getByRole('button', { name: 'Save as draft' }))
    await user.click(screen.getByRole('button', { name: 'Save as draft' }))
    answer(jsonResponse(201, {}))

    // Only the debounced autosave could follow, and it would be a PUT to the saved draft.
    expect(await screen.findByText('Saved as draft')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
  })
})
