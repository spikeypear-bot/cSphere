import './ui.css'
import type { EventRequestStatus } from '../../types/eventRequest'

interface TimelineStep {
  label: string
  state: 'done' | 'current' | 'upcoming' | 'rejected'
}

/** EO15 UX enhancement (docs/decision-log.md D17): status as a horizontal
 * progress stepper — reusing StepIndicator's visual language — rather than a
 * flat colour badge alone. Draft → Submitted → a final step whose label and
 * colour depend on the actual outcome, since "rejected"/"cancelled" are
 * terminal states, not just "further along" than "approved". */
function stepsFor(status: EventRequestStatus): TimelineStep[] {
  const draft: TimelineStep = { label: 'Draft', state: status === 'draft' ? 'current' : 'done' }
  const submitted: TimelineStep = {
    label: 'Submitted',
    state: status === 'draft' ? 'upcoming' : status === 'pending' ? 'current' : 'done',
  }

  if (status === 'approved') {
    return [draft, submitted, { label: 'Approved', state: 'done' }]
  }
  if (status === 'rejected') {
    return [draft, submitted, { label: 'Rejected', state: 'rejected' }]
  }
  if (status === 'cancelled') {
    return [draft, submitted, { label: 'Cancelled', state: 'rejected' }]
  }
  return [draft, submitted, { label: 'Under review', state: 'upcoming' }]
}

export function StatusTimeline({ status }: { status: EventRequestStatus }) {
  const steps = stepsFor(status)
  return (
    <ol className="status-timeline" aria-label={`Status: ${steps[steps.length - 1].label}`}>
      {steps.map((step) => (
        <li key={step.label} data-state={step.state}>
          {step.label}
        </li>
      ))}
    </ol>
  )
}
