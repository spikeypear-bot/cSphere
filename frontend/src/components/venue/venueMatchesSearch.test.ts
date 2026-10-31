// @stories DEV16 EC03 EC04
import { describe, expect, it } from 'vitest'
import type { VenueDto } from '../../types/venue'
import { venueMatchesSearch } from './venueMatchesSearch'

const venue: VenueDto = { venueId: 'room', venueName: 'Garden Hall', venueAddress: '10 Harbour Road',
  additionalInformation: 'Use the courtyard entrance.', venueCapacity: 100, supportedLayouts: [],
  venueAccessibilities: [], venueFacilities: [], operatingInformation: '' }
describe('DEV16 coordinator venue text matching', () => {
  it('DEV16 (happy): names, addresses and details match regardless of case and outer spaces', () => {
    for (const query of [' GARDEN ', 'Harbour', 'COURTYARD', '']) expect(venueMatchesSearch(venue, query)).toBe(true)
    expect(venueMatchesSearch(venue, 'unmatched')).toBe(false)
  })
  it('DEV16 (boundary): unnamed venues and absent notes remain searchable by address', () => {
    expect(venueMatchesSearch({ ...venue, venueName: undefined, additionalInformation: null }, 'harbour')).toBe(true)
    expect(venueMatchesSearch({ ...venue, venueName: undefined, additionalInformation: null }, 'undefined')).toBe(false)
  })
})
