// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { NotificationBell } from '../notifications/NotificationBell'
import { EquipmentRequestPage } from '../coordinator/EquipmentRequestPage'
import { EquipmentDamagePanel } from './EquipmentDamagePanel'
import type { EventEquipmentDamage } from '../technicalSupport/damage/equipmentDamage.types'
import type { ActivityDto } from '../../types/activity'

const SHORT: EventEquipmentDamage = {
  outcomeId: 'out-1', reportId: 'rep-1', equipmentId: 'eq-proj', equipmentName: 'Projector',
  serialNumber: 'EPS-EBU04-7K2Q9M', damagedQuantity: 1, description: 'Lamp exploded',
  periodStart: '2027-03-01T00:00:00Z', periodEnd: null, reportedAt: '2027-02-28T03:00:00Z',
  outcome: 'SHORT', shortfall: 1, replacementSerialNumber: null, decidedAt: '2027-02-28T04:00:00Z',
  resolution: null, resolutionNote: null, resolvedAt: null, additionalRequestId: null, additionalRequestStatus: null,
  canRequestAdditional: true, canResolve: true,
}

const REPLACED: EventEquipmentDamage = {
  ...SHORT, outcomeId: 'out-2', outcome: 'REPLACED', shortfall: 0, replacementSerialNumber: 'EPS-EBU04-AAAA22',
  canRequestAdditional: false, canResolve: false,
}

function renderPanel(readOnly = false) {
  return render(
    <MemoryRouter initialEntries={['/coordinator/events/event-1']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/event-1" element={<EquipmentDamagePanel eventId="event-1" readOnly={readOnly} />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('TS09 the coordinator sees damaged equipment and deals with a shortfall', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 AC17 (happy): lists each damaged item with its outcome and photo', async () => {
    stubApi({ 'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [SHORT, REPLACED]) })
    renderPanel()

    const items = within(await screen.findByRole('list', { name: 'Damaged equipment' })).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Projector · EPS-EBU04-7K2Q9M')
    expect(items[0]).toHaveTextContent('Short by 1')
    expect(items[0]).toHaveTextContent('Lamp exploded')
    expect(items[1]).toHaveTextContent('Replaced with unit EPS-EBU04-AAAA22')
    expect(within(items[1]).queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getAllByRole('img', { name: 'Projector' })).toHaveLength(2)
  })

  it('TS09 AC17 (negative): nothing is shown when no equipment was reported damaged', async () => {
    const calls = stubApi({ 'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, []) })
    renderPanel()

    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(screen.queryByRole('heading', { name: 'Damaged equipment' })).not.toBeInTheDocument()
  })

  it('TS09 AC17 (cross-cutting): the Lead reads the same outcomes without any action', async () => {
    seedSession('coordinator-lead')
    stubApi({ 'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [{ ...SHORT, canRequestAdditional: false, canResolve: false }]) })
    renderPanel(true)

    expect(await screen.findByText(/Read-only/)).toBeInTheDocument()
    expect(screen.getByText('Short by 1')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Request/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve with a note' })).not.toBeInTheDocument()
  })

  it('TS09 AC20 (happy): resolves a shortfall with a note', async () => {
    const resolved = { ...SHORT, resolution: 'NOTE' as const, resolutionNote: 'Organiser agreed one is enough',
      resolvedAt: '2027-02-28T05:00:00Z', canRequestAdditional: false, canResolve: false }
    const calls = stubApi({
      'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [SHORT]),
      'POST /api/events/event-1/equipment-damage/out-1/resolve': () => jsonResponse(200, resolved),
    })
    const user = userEvent.setup()
    renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Resolve with a note' }))
    await user.type(screen.getByLabelText(/Why the event can go ahead/), '  Organiser agreed one is enough ')
    await user.click(screen.getByRole('button', { name: 'Resolve shortfall' }))

    expect(await screen.findByText('Resolved with a note: Organiser agreed one is enough')).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ note: 'Organiser agreed one is enough' })
    expect(screen.queryByRole('button', { name: 'Resolve with a note' })).not.toBeInTheDocument()
  })

  it('TS09 AC20 (negative): a blank note is refused before sending', async () => {
    const calls = stubApi({ 'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [SHORT]) })
    const user = userEvent.setup()
    renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Resolve with a note' }))
    await user.type(screen.getByLabelText(/Why the event can go ahead/), '   ')
    await user.click(screen.getByRole('button', { name: 'Resolve shortfall' }))

    expect(screen.getByText('Explain why the shortfall is acceptable.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('TS09 AC20, AC22 (conflict): a resolution the server refuses shows its reason and nothing changes', async () => {
    stubApi({
      'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [SHORT]),
      'POST /api/events/event-1/equipment-damage/out-1/resolve': () =>
        jsonResponse(409, { message: 'Its additional request is awaiting Technical Support review.' }),
    })
    const user = userEvent.setup()
    renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Resolve with a note' }))
    await user.type(screen.getByLabelText(/Why the event can go ahead/), 'Fine without it')
    await user.click(screen.getByRole('button', { name: 'Resolve shortfall' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('awaiting Technical Support review')
    expect(screen.getByText('Short by 1')).toBeInTheDocument()
  })

  it('TS09 AC22 (failure): outcomes that cannot load say so and Try again reloads them', async () => {
    let calls = 0
    stubApi({
      'GET /api/events/event-1/equipment-damage': () => (++calls === 1
        ? jsonResponse(500, { message: 'Server unavailable' })
        : jsonResponse(200, [SHORT])),
    })
    const user = userEvent.setup()
    renderPanel()

    expect(await screen.findByText('Damaged equipment for this event could not be loaded.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Short by 1')).toBeInTheDocument()
  })

  it('TS09 AC19 (happy): requests the shortfall beside the approved request, sending which shortfall it answers', async () => {
    const approved = {
      requestId: 'req-1', eventId: 'event-1', status: 'approved', technicalRequirement: '', rejectReason: null,
      lines: [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }],
    }
    const created = { ...approved, requestId: 'req-2', status: 'processing', lines: [{ ...approved.lines[0], quantity: 1 }] }
    const calls = stubApi({
      'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [SHORT]),
      'GET /api/events/event-1': () => jsonResponse(200, {
        eventId: 'event-1', eventName: 'Town Hall', status: 'confirmed', equipmentRequirements: 'Two projectors',
      }),
      'GET /api/equipment/catalogue': () => jsonResponse(200, [
        { equipmentId: 'eq-proj', equipmentName: 'Projector', totalQuantity: 6, serialised: true },
      ]),
      'GET /api/events/event-1/equipment-requests': () => jsonResponse(200, [approved]),
      'POST /api/events/event-1/equipment-request': () => jsonResponse(201, created),
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/coordinator/events/event-1']}>
        <SessionProvider>
          <Routes>
            <Route path="/coordinator/events/:eventId" element={<EquipmentDamagePanel eventId="event-1" readOnly={false} />} />
            <Route path="/coordinator/events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('link', { name: 'Request 1 more' }))
    expect(await screen.findByText(/Additional request for the shortfall: 1 × Projector/)).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(1)
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Additional request for the shortfall submitted to Technical Support for review.')).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({
      technicalRequirement: 'Replaces 1 × Projector reported damaged.',
      items: [{ equipmentId: 'eq-proj', quantity: 1 }],
      shortfallOutcomeId: 'out-1',
    })
  })

  it('TS09 AC21 (negative): a shortfall that can no longer take a request keeps the usual one-request rule', async () => {
    const approved = {
      requestId: 'req-1', eventId: 'event-1', status: 'approved', technicalRequirement: '', rejectReason: null, lines: [],
    }
    stubApi({
      'GET /api/events/event-1': () => jsonResponse(200, { eventId: 'event-1', eventName: 'Town Hall', status: 'pending', equipmentRequirements: null }),
      'GET /api/equipment/catalogue': () => jsonResponse(200, []),
      'GET /api/events/event-1/equipment-requests': () => jsonResponse(200, [approved]),
      'GET /api/events/event-1/equipment-damage': () => jsonResponse(200, [{ ...SHORT, canRequestAdditional: false }]),
    })
    render(
      <MemoryRouter initialEntries={['/coordinator/events/event-1/equipment-request?shortfall=out-1']}>
        <SessionProvider>
          <Routes>
            <Route path="/coordinator/events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByText('This shortfall can no longer take an additional request.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Request already submitted' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit equipment request' })).not.toBeInTheDocument()
  })

  it('TS09 AC16 (happy): the outcome notification names the event and carries the outcome', async () => {
    const notice = {
      notificationId: 'n-1', type: 'equipment_damage_outcome', eventRequestId: null, eventId: 'event-1', eventName: 'Town Hall',
      newStatus: null, reason: null, coordinatorName: null, coordinatorEmail: null, isReassignment: null,
      occurredAt: new Date().toISOString(), read: false, linkPath: '/coordinator/events/event-1',
      message: 'Projector EPS-EBU04-7K2Q9M was reported damaged; Town Hall is short by 1.',
    }
    stubApi({
      'GET /api/notifications': () => jsonResponse(200, [notice]),
      'GET /api/notifications/unread-count': () => jsonResponse(200, { count: 1 }),
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/coordinator']}>
        <SessionProvider>
          <Routes><Route path="/coordinator" element={<NotificationBell />} /></Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    await user.click(await screen.findByLabelText(/Notifications/))
    expect(await screen.findByText('Damaged equipment affects "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText(/is short by 1/)).toBeInTheDocument()
  })

  it('TS09 AC17, AC20 (happy): the change log names the damage outcome and an accepted shortfall', () => {
    const base: ActivityDto = {
      activityId: 'a-1', type: 'equipment_damage_outcome', actorName: 'tech1', actorRole: 'technician',
      message: 'Projector short by 1', flaggedFields: [], fromStatus: null, toStatus: null, occurredAt: '2027-02-28T04:00:00Z',
    }
    render(<ActivityTimeline entries={[base, { ...base, activityId: 'a-2', type: 'equipment_shortfall_resolved', actorName: 'ec1', message: 'Fine without it' }]} />)

    expect(screen.getByText(/reported damaged equipment and decided the outcome/)).toBeInTheDocument()
    expect(screen.getByText(/accepted an equipment shortfall/)).toBeInTheDocument()
  })
})
