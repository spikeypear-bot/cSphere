import type { EventRequestStatus } from '../../types/eventRequest'
import './ui.css'

const LABELS: Record<EventRequestStatus, string> = {
  draft: 'Draft',
  pending: 'Submitted',
  clarification_required: 'Clarification required',
  approved: 'Approved',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}

export function StatusBadge({ status }: { status: EventRequestStatus }) {
  return <span className={`status-badge status-badge--${status}`}>{LABELS[status]}</span>
}
