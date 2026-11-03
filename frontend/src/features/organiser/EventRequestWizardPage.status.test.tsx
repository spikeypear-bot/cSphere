// @stories EO01 EO15  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EventRequestWizardPage } from './EventRequestWizardPage'

const ID = 'req-1'

function renderFor(status: string) {
  seedSession('organiser')
  stubApi({
    [`GET /api/event-requests/${ID}`]: () => jsonResponse(200, {
      requestId: ID, eventName: 'Alumni Homecoming', status, accessibilityNeeds: [], requiredFacilities: [],
    }),
  })
  render(
    <SessionProvider>
      <MemoryRouter initialEntries={[`/organiser/requests/${ID}`]}>
        <Routes>
          <Route path="/organiser/requests/:requestId" element={<EventRequestWizardPage />} />
          <Route path="/organiser/requests" element={<p>My event requests list</p>} />
        </Routes>
      </MemoryRouter>
    </SessionProvider>,
  )
}

// EC-NEW2: a request that has left draft names its actual status.
describe('EventRequestWizardPage on a request that is no longer a draft', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('EO15 (happy): a rejected request says it was rejected, not submitted', async () => {
    renderFor('rejected')
    expect(await screen.findByText(/This request was rejected, so it can no longer be edited/)).toBeInTheDocument()
    expect(screen.queryByText(/already been submitted/)).not.toBeInTheDocument()
  })

  it('EO15 (happy): a submitted request still says it was submitted', async () => {
    renderFor('pending')
    expect(await screen.findByText('This request has already been submitted and can no longer be edited here.')).toBeInTheDocument()
  })

  it('EO15 (happy): a request that has left draft offers the way back to the event requests list', async () => {
    renderFor('approved')
    await userEvent.click(await screen.findByRole('button', { name: 'Back to my event requests' }))
    expect(await screen.findByText('My event requests list')).toBeInTheDocument()
  })
})
