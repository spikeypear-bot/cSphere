import { JOURNEY_TONE, type JourneyCode, type StatusView } from '../../types/status'
import './JourneyStatus.css'

/**
 * EC-NEW2: the one status badge. The label and explanation come from the
 * server, so every screen and role sees exactly the same wording.
 */
export function JourneyStatusBadge({ status }: { status: StatusView }) {
  return (
    <span className="journey-status">
      <span className={`journey-badge journey-badge--${JOURNEY_TONE[status.code] ?? 'neutral'}`}
        title={status.explanation} data-code={status.code}>
        {status.label}
      </span>
      {status.datePassed ? <span className="journey-badge journey-badge--danger journey-badge--small">Date passed</span> : null}
    </span>
  )
}

/** The badge plus what the status means and who acts next. */
export function JourneyStatusExplainer({ status }: { status: StatusView }) {
  return (
    <div className="journey-explainer" data-tone={JOURNEY_TONE[status.code] ?? 'neutral'}>
      <JourneyStatusBadge status={status} />
      <p className="journey-explainer__text">
        {status.explanation}
        {status.nextActor ? <span className="journey-explainer__next"> Next: {status.nextActor}.</span> : null}
      </p>
      {status.datePassed ? (
        <p className="journey-explainer__warning">
          This event's date has passed without it being confirmed.
        </p>
      ) : null}
    </div>
  )
}

const STEPS: { label: string; codes: JourneyCode[] }[] = [
  { label: 'Request', codes: ['DRAFT', 'SUBMITTED'] },
  { label: 'Under review', codes: ['UNDER_REVIEW', 'CLARIFICATION_REQUIRED'] },
  { label: 'Planning', codes: ['PLANNING'] },
  { label: 'Safety check', codes: ['AWAITING_SAFETY_REVIEW', 'SAFETY_CHANGES_REQUESTED', 'SAFETY_APPROVED'] },
  { label: 'Confirmed', codes: ['CONFIRMED', 'COMPLETED'] },
]

/**
 * The whole journey at a glance, from submission to confirmation, with the
 * current step highlighted. Steps that need someone's attention (a
 * clarification, safety changes) are marked as such; Rejected and Cancelled
 * end the line.
 */
export function JourneyStepper({ status }: { status: StatusView }) {
  const ended = status.code === 'REJECTED' || status.code === 'CANCELLED'
  const current = STEPS.findIndex((s) => s.codes.includes(status.code))
  const attention = status.code === 'CLARIFICATION_REQUIRED' || status.code === 'SAFETY_CHANGES_REQUESTED'
  return (
    <ol className="journey-stepper" aria-label={`Progress: ${status.label}`}>
      {STEPS.map((step, index) => {
        let state: 'done' | 'current' | 'upcoming' | 'attention' | 'ended' = 'upcoming'
        if (ended) state = index === 0 ? 'done' : 'upcoming'
        else if (index < current || (status.code === 'COMPLETED' && index === current)) state = 'done'
        else if (index === current) state = attention ? 'attention' : status.code === 'SAFETY_APPROVED' ? 'done' : 'current'
        return (
          <li key={step.label} data-state={state} aria-current={index === current && !ended ? 'step' : undefined}>
            <span className="journey-stepper__dot" aria-hidden="true" />
            <span className="journey-stepper__label">{step.label}</span>
          </li>
        )
      })}
      {ended ? (
        <li data-state="ended">
          <span className="journey-stepper__dot" aria-hidden="true" />
          <span className="journey-stepper__label">{status.label}</span>
        </li>
      ) : null}
    </ol>
  )
}

/**
 * EC-NEW2: filter a list by status, with a count per status. Only statuses
 * present in the list are offered, so there is never an empty choice.
 */
export function StatusFilter({ statuses, selected, onChange, label = 'Filter by status' }: {
  statuses: StatusView[]
  selected: string | null
  onChange: (code: string | null) => void
  label?: string
}) {
  const counts = new Map<string, { label: string; count: number }>()
  statuses.forEach((s) => {
    const entry = counts.get(s.code) ?? { label: s.label, count: 0 }
    entry.count += 1
    counts.set(s.code, entry)
  })
  if (counts.size < 2) return null
  return (
    <div className="status-filter" role="group" aria-label={label}>
      <button type="button" className="chip" aria-pressed={selected === null} onClick={() => onChange(null)}>
        All <span className="status-filter__count">{statuses.length}</span>
      </button>
      {[...counts.entries()].map(([code, { label: name, count }]) => (
        <button key={code} type="button" className="chip" aria-pressed={selected === code}
          onClick={() => onChange(selected === code ? null : code)}>
          {name} <span className="status-filter__count">{count}</span>
        </button>
      ))}
    </div>
  )
}
