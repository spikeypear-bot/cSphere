// @stories TS02
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { buildRows } from './fulfilment'
import { ReserveCard } from './ReserveCard'
import type { EquipmentAvailability, EquipmentReservation, RequestLine, TimePeriod } from './reservation.types'
import { toLocalInputValue } from './reservationUtils'

// 9:00 am – 12:00 pm on 10 March 2027, Singapore time.
const START = '2027-03-10T01:00:00.000Z'
const END = '2027-03-10T04:00:00.000Z'
const request = {
  requestId: 'r1', eventId: 'event-1', eventName: 'Gala', eventStart: START, eventEnd: END,
  technicalRequirement: 'Stage', status: 'processing' as const,
}
const projector: RequestLine = { equipmentId: 'proj', equipmentName: 'Projector', quantity: 2 }
const mic: RequestLine = { equipmentId: 'mic', equipmentName: 'Microphone', quantity: 2 }
const stock = (line: RequestLine, serialised: boolean, availableQuantity: number): EquipmentAvailability => ({
  equipmentId: line.equipmentId, equipmentName: line.equipmentName, serialised, totalQuantity: 10, availableQuantity,
})
const unit = (serialNumber: string, status: 'Available' | 'Faulty' = 'Available') =>
  ({ equipmentId: 'proj', equipmentName: 'Projector', serialNumber, status })
const held = (serialNumber: string | null, extra: Partial<EquipmentReservation> = {}): EquipmentReservation => ({
  logId: `log-${serialNumber}`, eventId: 'event-1', equipmentId: 'proj', equipmentName: 'Projector',
  quantity: 1, serialNumber, loanedFrom: START, loanedUntil: END, ...extra,
})

function Harness({ lines = [projector], availability = [stock(projector, true, 5)], units = ['P-1', 'P-2', 'P-3', 'P-4'].map((s) => unit(s)),
  reservations = [], kindReservations = {}, loadFailed = false, onRetry = () => {}, onRefresh = () => {} }: {
  lines?: RequestLine[]
  availability?: EquipmentAvailability[]
  units?: ReturnType<typeof unit>[]
  reservations?: EquipmentReservation[]
  kindReservations?: Record<string, EquipmentReservation[]>
  loadFailed?: boolean
  onRetry?: () => void
  onRefresh?: () => void
}) {
  const [period, setPeriod] = useState<TimePeriod>({ start: toLocalInputValue(START), end: toLocalInputValue(END) })
  const rows = loadFailed ? null : buildRows({
    lines, availability, eventReservations: reservations, units, kindReservations,
    eventId: 'event-1', eventStart: START, eventEnd: END, window: { from: START, to: END },
  })
  return (
    <ReserveCard request={request} rows={rows} lines={lines} reservations={reservations} loaded loadFailed={loadFailed}
      onRetry={onRetry} period={period} onPeriodChange={setPeriod} periodValid onRefresh={onRefresh} />
  )
}

describe('TS02 reserve card', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS02 (failure): figures that could not load say so, and Try again asks for them again', async () => {
    const onRetry = vi.fn()
    const user = userEvent.setup()
    render(<Harness loadFailed onRetry={onRetry} />)

    expect(screen.getByRole('alert')).toHaveTextContent('The equipment figures could not be loaded.')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('TS02 (boundary): a request with no itemised equipment points to its technical note and offers nothing to reserve', () => {
    render(<Harness lines={[]} />)

    expect(screen.getByText('This request lists no itemised equipment. Review its technical note instead.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reserve all/ })).not.toBeInTheDocument()
  })

  it('TS02 (boundary): an item with no availability figure shows a dash and cannot be reserved', () => {
    render(<Harness lines={[mic]} availability={[]} />)

    expect(within(screen.getByRole('row', { name: /Microphone/ })).getByText('—')).toBeInTheDocument()
    expect(screen.getByText('None free for this window: all 2 still needed are short.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nothing to reserve' })).toBeDisabled()
  })

  it('TS02 (happy): the units list says what each unit is doing in the loan window', async () => {
    const user = userEvent.setup()
    render(<Harness
      units={['P-1', 'P-2', 'P-3', 'P-4', 'P-5'].map((s) => unit(s, s === 'P-5' ? 'Faulty' : 'Available'))}
      reservations={[held('P-1'), held('P-2', { spare: true })]}
      kindReservations={{ proj: [held('P-3', { eventId: 'other', eventName: undefined })] }} />)

    await user.click(screen.getByRole('button', { name: /^Units/ }))
    const items = screen.getAllByRole('listitem').filter((li) => li.classList.contains('fulfil-unit'))
    expect(items.map((li) => li.textContent)).toEqual([
      'P-1Reserved for this event', 'P-2Spare for this event', 'P-3Reserved', 'P-4Free', 'P-5Faulty',
    ])
  })

  it('TS02 (boundary): a serialised item with no units recorded says so', async () => {
    const user = userEvent.setup()
    render(<Harness units={[]} />)

    await user.click(screen.getByRole('button', { name: /^Units/ }))
    expect(screen.getByText('No units of this item are recorded.')).toBeInTheDocument()
  })

  it('TS02 (happy): spares can be added and removed one at a time, and a spare unit is tagged as such', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Add a spare Projector' }))
    await user.click(screen.getByRole('button', { name: 'One more spare' }))
    expect(screen.getByText('2 spare')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Units to reserve: Projector' })).getAllByRole('listitem')
      .map((chip) => chip.textContent)).toEqual(['P-1auto', 'P-2auto', 'P-3spare', 'P-4spare'])
    expect(screen.getByRole('button', { name: 'One more spare' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Reserve all remaining (4 units)' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'One fewer spare' }))
    await user.click(screen.getByRole('button', { name: 'One fewer spare' }))
    expect(screen.getByRole('button', { name: 'Add a spare Projector' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve all remaining (2 units)' })).toBeEnabled()
  })

  it('TS02 (boundary): clearing reserve-now means none, and nothing is left to send', () => {
    render(<Harness />)

    fireEvent.change(screen.getByLabelText('Reserve now: Projector'), { target: { value: '' } })
    expect(screen.getByLabelText('Reserve now: Projector')).toHaveValue(0)
    expect(screen.getByRole('button', { name: 'Nothing to reserve' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Reserve now: Projector'), { target: { value: '1' } })
    expect(screen.getByRole('button', { name: 'Reserve all remaining (1 unit)' })).toBeEnabled()
  })

  it('TS02 (happy): chosen units can be handed back to automatic picking, and the chooser closes from either control', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: /Choose units/ }))
    await user.click(screen.getByRole('checkbox', { name: 'P-1' }))
    await user.click(screen.getByRole('checkbox', { name: 'P-4' }))
    expect(screen.getByRole('checkbox', { name: 'P-3' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /Choose units/ }))
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Units to reserve: Projector' })).getAllByRole('listitem')
      .map((chip) => chip.textContent)).toEqual(['P-2', 'P-4'])

    await user.click(screen.getByRole('button', { name: /Choose units/ }))
    await user.click(screen.getByRole('button', { name: 'Pick automatically' }))
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Units to reserve: Projector' })).getAllByRole('listitem')
      .map((chip) => chip.textContent)).toEqual(['P-1auto', 'P-2auto'])
  })

  it('TS02 (boundary): a changed loan window can be put back to the event’s times in one step', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.queryByRole('button', { name: 'Use the event’s times' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Start'), { target: { value: '2027-03-10T08:00' } })
    expect(screen.queryByText(/· the event’s times/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use the event’s times' }))
    expect(screen.getByLabelText('Start')).toHaveValue('2027-03-10T09:00')
    expect(screen.getByText(/· the event’s times/)).toBeInTheDocument()
  })

  it('TS02 (happy): a reservation held for other times than the event shows those times', () => {
    render(<Harness reservations={[held('P-1', { loanedFrom: '2027-03-10T00:00:00Z', loanedUntil: '2027-03-10T02:00:00Z' })]} />)

    const reserved = screen.getByRole('region', { name: 'Reserved for this event' })
    expect(within(reserved).getByText('P-1')).toBeInTheDocument()
    expect(reserved).toHaveTextContent(/8:00 am – 10:00 am/)
  })

  it.each([
    [400, 'That reservation request was invalid.'],
    [404, 'That equipment could not be found.'],
    [500, 'Could not save the reservations. Please try again.'],
  ])('TS02 (failure): a refused batch (%i) with no message says why, does not refresh, and the message clears on the next change', async (status, text) => {
    const onRefresh = vi.fn()
    stubApi({ 'POST /api/equipment/reservations/batch': () => jsonResponse(status, { message: '' }) })
    const user = userEvent.setup()
    render(<Harness onRefresh={onRefresh} />)

    await user.click(screen.getByRole('button', { name: 'Reserve all remaining (2 units)' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(screen.getByRole('alert')).not.toHaveTextContent('Nothing was reserved')
    expect(onRefresh).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Add a spare Projector' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('TS02 (failure): an unreadable answer from the server says the reservations could not be saved', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 201, text: async () => 'not json' }) as Response))
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Reserve all remaining (2 units)' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the reservations. Please try again.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reserve all remaining (2 units)' })).toBeEnabled())
  })
})
