import { formatSingaporeDateTime } from '../../lib/singaporeTime'
import type { ReadinessDto } from './readinessTypes'

const OUTCOME: Record<ReadinessDto['approvalStatus'], string> = {
  not_reviewed: 'Not reviewed yet',
  approved: 'Approved',
  changes_requested: 'Changes requested',
  rejected: 'Rejected',
}

/**
 * SO-C5/SO-C6: the Safety Officer's latest decision on an event, with its
 * reason and the checklist items it concerns. Shown to the coordinator, and
 * to the Safety Officer when the event comes back for review.
 */
export function SafetyDecisionSummary({ readiness }: { readiness: ReadinessDto }) {
  const labels = new Map(readiness.items.map((item) => [item.itemKey, item.label]))
  const flagged = readiness.flaggedItems ?? []
  return (
    <div className="safety-decision" data-outcome={readiness.approvalStatus}>
      <p className="safety-decision__outcome">
        <strong>{OUTCOME[readiness.approvalStatus]}</strong>
        {readiness.reviewedBy ? (
          <span className="safety-decision__by">
            {' '}by {readiness.reviewedBy}
            {readiness.reviewedAt ? ` · ${formatSingaporeDateTime(readiness.reviewedAt)} (Singapore time)` : ''}
          </span>
        ) : null}
      </p>
      {readiness.approvalNote ? (
        <blockquote className="safety-decision__reason">{readiness.approvalNote}</blockquote>
      ) : null}
      {flagged.length > 0 ? (
        <>
          <p className="safety-decision__items-heading">Checklist items concerned:</p>
          <ul className="safety-decision__items" aria-label="Checklist items concerned">
            {flagged.map((key) => <li key={key}>{labels.get(key) ?? key}</li>)}
          </ul>
        </>
      ) : null}
    </div>
  )
}
