import type { ActivityDto, ActivityTypeName } from '../../types/activity'
import { FIELD_LABELS } from '../../types/eventRequest'
import { formatRelativeTime } from '../../lib/relativeTime'
import './ActivityTimeline.css'

const ROLE_LABELS: Record<string, string> = {
  ec: 'Event Coordinator',
  eo: 'Event Organiser',
  vs: 'Venue Staff',
  technician: 'Technical Support',
  attendee: 'Attendee',
}

const STATUS_LABELS: Record<string, string> = {
  draft: 'Draft',
  pending: 'Submitted',
  clarification_required: 'Clarification required',
  approved: 'Approved',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}

/** What happened, in words, with the actor as the subject. */
const TITLES: Record<ActivityTypeName, string> = {
  submitted: 'submitted the request',
  coordinator_assigned: 'is coordinating this request',
  clarification_requested: 'asked for clarification',
  clarification_responded: 'responded and resubmitted',
  approved: 'approved the request. Planning has started',
  rejected: 'rejected the request',
  venue_booking_requested: 'requested a venue',
}

/** Entries whose message is a conversation turn, shown as a speech bubble. */
const CONVERSATIONAL: ActivityTypeName[] = ['clarification_requested', 'clarification_responded', 'rejected']

function exactTime(iso: string) {
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(new Date(iso))
}

/**
 * The request's audit trail (EC01/EC02/EO26), oldest first: who did what,
 * when, what they said, and how the status moved. The server decides which
 * entries a role may see; this only renders them. Read-only by design, since
 * entries are append-only in the database.
 */
export function ActivityTimeline({ entries, emptyText = 'Nothing has happened yet.' }: {
  entries: ActivityDto[]
  emptyText?: string
}) {
  if (entries.length === 0) {
    return <p className="field-hint">{emptyText}</p>
  }
  return (
    <ol className="activity-timeline" aria-label="Request timeline">
      {entries.map((entry) => (
        <li key={entry.activityId} className="activity-timeline__entry" data-type={entry.type}
          data-side={entry.actorRole === 'eo' ? 'organiser' : 'internal'}>
          <span className="activity-timeline__dot" aria-hidden="true" />
          <div className="activity-timeline__body">
            <p className="activity-timeline__title">
              <strong>{entry.actorName}</strong>{' '}
              <span className="activity-timeline__role">({ROLE_LABELS[entry.actorRole] ?? entry.actorRole})</span>{' '}
              {TITLES[entry.type] ?? entry.type}
            </p>
            <p className="activity-timeline__time">
              <time dateTime={entry.occurredAt} title={exactTime(entry.occurredAt)}>
                {formatRelativeTime(entry.occurredAt)}
              </time>
              <span aria-hidden="true"> · </span>
              <span>{exactTime(entry.occurredAt)}</span>
            </p>
            {entry.message ? (
              CONVERSATIONAL.includes(entry.type)
                ? <blockquote className="activity-timeline__message">{entry.message}</blockquote>
                : <p className="activity-timeline__note">{entry.message}</p>
            ) : null}
            {entry.flaggedFields.length > 0 ? (
              <ul className="activity-timeline__flags" aria-label="Fields that need attention">
                {entry.flaggedFields.map((field) => (
                  <li key={field}>{FIELD_LABELS[field] ?? field}</li>
                ))}
              </ul>
            ) : null}
            {entry.fromStatus && entry.toStatus ? (
              <p className="activity-timeline__status">
                {STATUS_LABELS[entry.fromStatus] ?? entry.fromStatus}
                <span aria-label="changed to"> → </span>
                {STATUS_LABELS[entry.toStatus] ?? entry.toStatus}
              </p>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  )
}
