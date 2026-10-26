// Mirrors ReadinessService.STANDARD_ITEMS (SO-C3). Readiness responses carry
// their own labels; this is for places that only have the keys, such as a
// safety decision's timeline entry (SO-C5/SO-C6).
export const CHECKLIST_ITEM_LABELS: Record<string, string> = {
  venue_booking: 'Venue booking is approved and suitable',
  capacity_facilities: 'Venue capacity and required facilities are verified',
  accessibility: 'Accessibility needs are arranged',
  equipment: 'Required equipment is reserved or confirmed unavailable',
  registration: 'Registration and attendance arrangements are ready',
  safety_operations: 'Safety, emergency, and on-site operational arrangements are ready',
}
