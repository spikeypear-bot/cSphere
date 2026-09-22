import type { AccessibilityFeature } from './eventRequest'

export const venueLayouts = ['classroom', 'theatre', 'boardroom', 'banquet', 'exhibition'] as const
export type VenueLayout = typeof venueLayouts[number]
export const venueLayoutLabels: Record<VenueLayout, string> = {
  classroom: 'Classroom', theatre: 'Theatre', boardroom: 'Boardroom', banquet: 'Banquet', exhibition: 'Exhibition',
}
export interface CreateVenueDto {
  venueAddress: string
  venueCapacity: number | null
  supportedLayouts: VenueLayout[]
  venueAccessibilities: AccessibilityFeature[]
  venueFacilities: Facility[]
  operatingInformation: string
  additionalInformation: string | null
}
/** One independently bookable room or space; venueAddress is its displayed identity and location. */
export interface VenueDto extends CreateVenueDto { venueId: string }


export const venueAccessibilities = ['accessible_parking', 'drop_off_zone', 'public_transport',
  'step_free_access', 'wide_doorways', 'elevators', 'wheelchair_support', 'none'] as const satisfies readonly AccessibilityFeature[]
export const venueAccessibilityLabels: Record<AccessibilityFeature, string> = {
  accessible_parking: 'Accessible parking', drop_off_zone: 'Drop-off zone', public_transport: 'Near public transport',
  step_free_access: 'Step-free access', wide_doorways: 'Wide doorways', elevators: 'Elevators',
  wheelchair_support: 'Wheelchair support', none: 'No accessibility provisions',
}
export const venueFacilities = ['audio_visual_equipment', 'air_conditioning', 'breakout_spaces',
  'projection', 'stage', 'dining_area', 'barbeque_pit'] as const
export type Facility = typeof venueFacilities[number]
export const venueFacilityLabels: Record<Facility, string> = {
  audio_visual_equipment: 'Audio-visual equipment', air_conditioning: 'Air conditioning',
  breakout_spaces: 'Breakout spaces', projection: 'Projection', stage: 'Stage',
  dining_area: 'Dining area', barbeque_pit: 'Barbeque pit',
}
