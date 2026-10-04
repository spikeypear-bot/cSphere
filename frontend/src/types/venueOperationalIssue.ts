export interface VenueOperationalIssueDto {
  issueId: string
  venueId: string
  description: string
  affectedFrom: string | null
  affectedUntil: string | null
  createdBy: string
  createdAt: string
}

export interface CoordinatorVenueOperationalIssueDto {
  issueId: string
  venueId: string
  venueAddress: string
  description: string
  affectedFrom: string | null
  affectedUntil: string | null
  createdAt: string
  overlappingEvents: {
    eventId: string
    eventName: string
    startDatetime: string
    endDatetime: string
  }[]
}
