import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
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
        <Routes><Route path="/organiser/requests/:requestId" element={<EventRequestWizardPage />} /></Routes>
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

  it('says a rejected request was rejected, not submitted', async () => {
    renderFor('rejected')
    expect(await screen.findByText(/This request was rejected, so it can no longer be edited/)).toBeInTheDocument()
    expect(screen.queryByText(/already been submitted/)).not.toBeInTheDocument()
  })

  it('still says a submitted request was submitted', async () => {
    renderFor('pending')
    expect(await screen.findByText('This request has already been submitted and can no longer be edited here.')).toBeInTheDocument()
  })
})
