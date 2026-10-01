export interface VenueOperationalIssueDto {
  issueId: string
  venueId: string
  description: string
  affectedFrom: string | null
  affectedUntil: string | null
  createdBy: string
  createdAt: string
}
