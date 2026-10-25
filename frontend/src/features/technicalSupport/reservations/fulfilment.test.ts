// @stories TS02 TS01 TS06
import { describe, expect, it } from 'vitest'
import { buildRows, describeReserved, groupReservations, planBatch, rowProblem, unitsFor, NO_CHOICE } from './fulfilment'
import type { EquipmentReservation } from './reservation.types'

const START = '2027-03-10T01:00:00Z'
const END = '2027-03-10T04:00:00Z'
const window = { from: START, to: END }

function held(equipmentId: string, quantity: number, extra: Partial<EquipmentReservation> = {}): EquipmentReservation {
  return {
    logId: `log-${Math.random()}`, eventId: 'event-1', equipmentId, equipmentName: equipmentId,
    quantity, serialNumber: null, loanedFrom: START, loanedUntil: END, ...extra,
  }
}

function rows(input: Partial<Parameters<typeof buildRows>[0]> = {}) {
  return buildRows({
    lines: [
      { equipmentId: 'proj', equipmentName: 'Projector', quantity: 2 },
      { equipmentId: 'mic', equipmentName: 'Microphone', quantity: 4 },
    ],
    availability: [
      { equipmentId: 'proj', equipmentName: 'Projector', serialised: true, totalQuantity: 5, availableQuantity: 3 },
      { equipmentId: 'mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 10, availableQuantity: 6 },
    ],
    eventReservations: [],
    units: ['P-3', 'P-1', 'P-10', 'P-2', 'P-4'].map((serialNumber) => ({
      equipmentId: 'proj', equipmentName: 'Projector', serialNumber, status: serialNumber === 'P-1' ? 'Faulty' as const : 'Available' as const,
    })),
    kindReservations: { proj: [held('proj', 1, { eventId: 'other', serialNumber: 'P-2', eventName: 'Board Offsite' })] },
    eventId: 'event-1',
    eventStart: START,
    eventEnd: END,
    window,
    ...input,
  })
}

describe('TS02 request fulfilment rows (D33)', () => {
  it('TS02 (happy): a serialised row offers only free, working units, in the server’s serial order, and defaults to what remains', () => {
    const [projector] = rows()
    expect(projector.freeSerials).toEqual(['P-10', 'P-3', 'P-4'])
    expect(projector.units.map((u) => [u.serialNumber, u.state.kind])).toEqual([
      ['P-1', 'blocked'], ['P-10', 'free'], ['P-2', 'elsewhere'], ['P-3', 'free'], ['P-4', 'free'],
    ])
    expect(projector.capacity).toBe(3)
    expect(unitsFor(projector, NO_CHOICE)).toEqual(['P-10', 'P-3'])
  })

  it('TS02 TS06 (happy): spares are counted apart but cover the request, as TS06 counts them', () => {
    const [, mic] = rows({ eventReservations: [held('mic', 3), held('mic', 1, { spare: true })] })
    expect(mic.reserved).toBe(3)
    expect(mic.spareReserved).toBe(1)
    expect(mic.remaining).toBe(0)
    expect(describeReserved(mic)).toBe('3 + 1 spare')
  })

  it('TS02 (boundary): a reservation that does not cover the whole event does not count as reserved', () => {
    const [, mic] = rows({ eventReservations: [held('mic', 4, { loanedFrom: '2027-03-10T01:01:00Z' })] })
    expect(mic.reserved).toBe(0)
    expect(mic.remaining).toBe(4)
  })

  it('TS02 (boundary): reserve-now defaults to what remains, never more than is free, and more than remains must be spare', () => {
    const [projector, mic] = rows({ availability: [
      { equipmentId: 'proj', equipmentName: 'Projector', serialised: true, totalQuantity: 5, availableQuantity: 1 },
      { equipmentId: 'mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 10, availableQuantity: 6 },
    ] })
    expect(planBatch([projector, mic], {})).toEqual([
      { equipmentId: 'proj', quantity: 1, spare: 0, serialNumbers: ['P-10'] },
      { equipmentId: 'mic', quantity: 4, spare: 0 },
    ])
    expect(rowProblem(mic, { ...NO_CHOICE, now: 5 })).toBe('Only 4 still requested. Add any extra units as spares.')
    expect(rowProblem(mic, { ...NO_CHOICE, now: 4, spare: 2 })).toBeNull()
    expect(rowProblem(mic, { ...NO_CHOICE, now: 4, spare: 3 })).toBe('Only 6 can be reserved for this window.')
  })

  it('TS02 (negative): chosen units must match the units asked for, and the extra ones are sent as spares', () => {
    const [projector] = rows()
    expect(rowProblem(projector, { now: 2, spare: 0, serials: ['P-4'] })).toBe('Choose 1 more unit.')
    expect(planBatch([projector], { proj: { now: 1, spare: 1, serials: ['P-4', 'P-3'] } })).toEqual([
      { equipmentId: 'proj', quantity: 1, spare: 1, serialNumbers: ['P-3', 'P-4'] },
    ])
    expect(planBatch([projector], { proj: { now: 0, spare: 0, serials: null } })).toEqual([])
  })

  it('TS02 (happy): saved reservations are grouped by item in request order', () => {
    const groups = groupReservations([held('mic', 2), held('proj', 1, { serialNumber: 'P-3' }), held('mic', 1, { spare: true })],
      [{ equipmentId: 'proj', equipmentName: 'Projector', quantity: 2 }, { equipmentId: 'mic', equipmentName: 'Microphone', quantity: 4 }])
    expect(groups.map((g) => [g.equipmentId, g.reservations.length])).toEqual([['proj', 1], ['mic', 2]])
  })
})
