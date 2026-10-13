import type { EventRequestStatus } from '../../types/eventRequest'
import type { StatusView } from '../../types/status'
import { JourneyStatusBadge } from './JourneyStatus'
import './ui.css'

const LABELS: Record<EventRequestStatus, string> = {
  draft: 'Draft',
  pending: 'Submitted',
  clarification_required: 'Clarification required',
  approved: 'Approved',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}

/** EC-NEW2: when the server sent the journey status, that is what is shown,
 * so this badge matches every other screen; the stored status is only a
 * fallback for responses that do not carry one. */
export function StatusBadge({ status, journey }: { status: EventRequestStatus; journey?: StatusView | null }) {
  if (journey) return <JourneyStatusBadge status={journey} />
  return <span className={`status-badge status-badge--${status}`}>{LABELS[status]}</span>
}
