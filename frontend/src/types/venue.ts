export const venueLayouts = ['classroom', 'theatre', 'boardroom', 'banquet', 'exhibition'] as const
export type VenueLayout = typeof venueLayouts[number]
export const venueLayoutLabels: Record<VenueLayout, string> = {
  classroom: 'Classroom', theatre: 'Theatre', boardroom: 'Boardroom', banquet: 'Banquet', exhibition: 'Exhibition',
}
export interface CreateVenueDto {
  venueAddress: string
  venueCapacity: number | null
  supportedLayouts: VenueLayout[]
  operatingInformation: string
  additionalInformation: string | null
}
export interface VenueDto extends CreateVenueDto { venueId: string }
