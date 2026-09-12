import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { EventRequestWizardPage } from './EventRequestWizardPage'

const DRAFT_ID = '11111111-1111-1111-1111-111111111111'

function seedSession() {
  window.localStorage.setItem(
    'connectsphere.session',
    JSON.stringify({ role: 'organiser', organisation: 'Acme Conferences' }),
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response
}

function renderWizard() {
  return render(
    <MemoryRouter initialEntries={['/organiser/requests/new']}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/new" element={<EventRequestWizardPage />} />
          <Route path="/organiser" element={<div>Organiser home</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EventRequestWizardPage — EO01 (save a draft) and EO02 (submit)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('lets the organiser move through every step without filling any field in', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests' && init?.method === 'POST') {
        return jsonResponse(201, {
          requestId: DRAFT_ID,
          status: 'draft',
          eventName: null,
          purpose: null,
          description: null,
          startDatetime: null,
          endDatetime: null,
          expectedAttendance: null,
          venueRequirements: null,
          equipmentRequirements: null,
          accessibilityNeeds: [],
          registrationNeeds: null,
          organisation: 'Acme Conferences',
          createdAt: new Date().toISOString(),
        })
      }
      if (url === `/api/event-requests/${DRAFT_ID}` && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body))
        return jsonResponse(200, {
          requestId: DRAFT_ID,
          status: 'draft',
          organisation: 'Acme Conferences',
          createdAt: new Date().toISOString(),
          accessibilityNeeds: [],
          ...body,
        })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderWizard()

    // Step 1 of 5: Basics — deliberately left blank.
    expect(screen.getByLabelText('Event name')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    // Step 2: Schedule & attendance — also left blank.
    expect(await screen.findByLabelText('Expected attendance')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    // Step 3: Venue & accessibility.
    expect(await screen.findByLabelText('Venue requirements')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    // Step 4: Equipment & registration.
    expect(await screen.findByLabelText('Equipment requirements')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    // Step 5: Review — reaching it at all, with nothing filled in, is EO01's
    // "can save an event request as a draft while required fields are
    // incomplete" acceptance criterion.
    expect(await screen.findByRole('button', { name: /submit for review/i })).toBeInTheDocument()

    // Every intermediate save was a draft save, never a rejection.
    const draftSaves = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST' || init?.method === 'PUT')
    expect(draftSaves.length).toBeGreaterThan(0)
  })

  it('shows exactly the missing fields the backend reports when submitting an incomplete request', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests' && init?.method === 'POST') {
        return jsonResponse(201, {
          requestId: DRAFT_ID,
          status: 'draft',
          accessibilityNeeds: [],
          organisation: 'Acme Conferences',
          createdAt: new Date().toISOString(),
        })
      }
      if (url === `/api/event-requests/${DRAFT_ID}` && init?.method === 'PUT') {
        return jsonResponse(200, {
          requestId: DRAFT_ID,
          status: 'draft',
          accessibilityNeeds: [],
          organisation: 'Acme Conferences',
          createdAt: new Date().toISOString(),
        })
      }
      if (url === `/api/event-requests/${DRAFT_ID}/submit`) {
        return jsonResponse(422, {
          message: 'Event request is missing required fields: eventName, purpose',
          missingFields: ['eventName', 'purpose'],
        })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderWizard()

    for (let i = 0; i < 4; i += 1) {
      await user.click(await screen.findByRole('button', { name: 'Next' }))
    }

    await user.click(await screen.findByRole('button', { name: /submit for review/i }))

    const missing = await screen.findByRole('alert')
    expect(within(missing).getByText('Event name')).toBeInTheDocument()
    expect(within(missing).getByText('Purpose')).toBeInTheDocument()
  })
})
