// @stories DEV16
import { describe, expect, it } from 'vitest'
import { filterVenues, groupVenues, hasCoordinates, venueName } from './mapData'
import type { VenueDto } from '../../types/venue'

const room = (id: string, changes: Partial<VenueDto> = {}): VenueDto => ({
  venueId: id, venueName: `Room ${id}`, venueAddress: 'School, Singapore', venueCapacity: 100,
  venueFacilities: ['projection', 'air_conditioning'], venueAccessibilities: [], supportedLayouts: ['theatre'],
  operatingInformation: '', additionalInformation: null, latitude: 1.3, longitude: 103.8, ...changes,
})
describe('DEV16 map data', () => {
  it('DEV16 (boundary): invalid or partial coordinates never create map markers', () => {
    for (const changes of [{ latitude: null }, { longitude: undefined }, { latitude: NaN }, { longitude: Infinity }, { latitude: 91 }, { longitude: -181 }]) {
      expect(hasCoordinates(room('a', changes))).toBe(false)
    }
    expect(hasCoordinates(room('zero', { latitude: 0, longitude: 0 }))).toBe(true)
    expect(hasCoordinates(room('edge', { latitude: -90, longitude: 180 }))).toBe(true)
  })
  it('DEV16 (conflict): shared and nearby locations retain independently selectable room IDs', () => {
    const groups = groupVenues([room('a'), room('b'), room('near', { latitude: 1.3001 }), room('far', { latitude: 1.31 }), room('missing', { latitude: null })])
    expect(groups.map(g => g.venues.map(v => v.venueId))).toEqual([['a', 'b', 'near'], ['far']])
  })
  it('DEV16 (happy): name/address search, capacity boundary, all required facilities and date combine', () => {
    const venues = [room('a'), room('b', { venueCapacity: 99 }), room('c', { venueFacilities: ['projection'] }), room('d')]
    expect(filterVenues(venues, '  SINGAPORE ', '100', ['projection', 'air_conditioning'], '2027-05-01', [
      { venueId: 'a', eventCount: 1, pendingBookingCount: 0, matchingBookingCount: 1 },
      { venueId: 'b', eventCount: 1, pendingBookingCount: 0, matchingBookingCount: 1 },
      { venueId: 'c', eventCount: 1, pendingBookingCount: 0, matchingBookingCount: 1 },
    ]).map(v => v.venueId)).toEqual(['a'])
    expect(filterVenues(venues, 'room A', '', [], '', []).map(v => v.venueId)).toEqual(['a'])
  })
  it('DEV16 (boundary): venues missing coordinates stay searchable and no-match searches stay empty', () => {
    const venues = [room('missing', { latitude: null })]
    expect(filterVenues(venues, 'missing', '', [], '', [])).toEqual(venues)
    expect(filterVenues(venues, 'unknown', '', [], '', [])).toEqual([])
  })
  it('DEV16 (boundary): unnamed rooms use their address and unknown capacity never satisfies a minimum', () => {
    const unnamed = room('legacy', { venueName: '   ', venueCapacity: null })
    expect(venueName(unnamed)).toBe('School, Singapore')
    expect(venueName(room('absent', { venueName: undefined }))).toBe('School, Singapore')
    expect(filterVenues([unnamed], 'school', '', [], '', [])).toEqual([unnamed])
    expect(filterVenues([unnamed], '', '1', [], '', [])).toEqual([])
    expect(filterVenues([unnamed], '', '', [], '2027-05-01', [
      { venueId: 'legacy', eventCount: 0, pendingBookingCount: 0, matchingBookingCount: 0 },
    ])).toEqual([])
  })
})
