import type { VenueHold } from '../../types/venueHold'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'
import { formatEventDateTime } from './formatEventDateTime'
import './HoldStatus.css'

const LABELS: Record<VenueHold['status'], string> = {
  active: 'Active', expired: 'Expired', released: 'Released', converted: 'Converted to approved booking',
}
const REMINDER_DUE = (h: VenueHold) => h.reminderDueAt

/** VS-C3 hold history; EC-C3 marks an active hold "Expiring soon" from the server's reminderDueAt. */
export function HoldStatus({ holds = NO_HOLDS }: { holds?: VenueHold[] }) {
  const expired = useHoldDeadline(holds)
  const reminderDue = useHoldDeadline(holds, undefined, REMINDER_DUE)
  if (!holds.length) return null
  return <ul aria-label="Tentative hold history" className="hold-status">{holds.map(h => {
    const status = expired.has(h.holdId) && h.status === 'active' ? 'expired' : h.status
    return <li key={h.holdId}>
      Tentative hold: {LABELS[status]}
      {' · Expiry (SGT): '}{formatEventDateTime(h.expiresAt)}
      {status === 'active' && reminderDue.has(h.holdId)
        ? <> <span className="hold-status__expiring">Expiring soon</span></> : null}
    </li>
  })}</ul>
}
