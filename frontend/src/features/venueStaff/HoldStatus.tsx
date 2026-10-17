import type { VenueHold } from '../../types/venueHold'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'
import { formatEventDateTime } from './formatEventDateTime'

export function HoldStatus({ holds = NO_HOLDS }: { holds?: VenueHold[] }) {
  const expired = useHoldDeadline(holds)
  if (!holds.length) return null
  return <ul aria-label="Tentative hold history">{holds.map(h => <li key={h.holdId}>
    Tentative hold: {expired.has(h.holdId) && h.status === 'active' ? 'expired' : h.status}
    {' · Expiry (SGT): '}{formatEventDateTime(h.expiresAt)}
  </li>)}</ul>
}
