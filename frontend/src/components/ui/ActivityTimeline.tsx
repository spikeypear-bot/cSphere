import { useState } from 'react'
import type { ActivityDto, ActivityTypeName } from '../../types/activity'
import { FIELD_LABELS } from '../../types/eventRequest'
import { formatRelativeTime } from '../../lib/relativeTime'
import { changedFields } from '../../lib/fieldChanges'
import { JOURNEY_LABELS } from '../../types/status'
import './ActivityTimeline.css'

const ROLE_LABELS: Record<string, string> = {
  ec: 'Event Coordinator',
  ecl: 'Event Coordinator Lead',
  eo: 'Event Organiser',
  vs: 'Venue Staff',
  technician: 'Technical Support',
  safety_officer: 'Safety Officer',
  attendee: 'Attendee',
}

// Request entries store request statuses; event entries (EC08 onwards) store
// journey codes (EC-NEW2), so both are labelled here in the same vocabulary.
// An approved request is an event in Planning; "Approved" is not a status.
const STATUS_LABELS: Record<string, string> = {
  ...JOURNEY_LABELS,
  draft: JOURNEY_LABELS.DRAFT,
  pending: JOURNEY_LABELS.SUBMITTED,
  clarification_required: JOURNEY_LABELS.CLARIFICATION_REQUIRED,
  approved: JOURNEY_LABELS.PLANNING,
  rejected: JOURNEY_LABELS.REJECTED,
  cancelled: JOURNEY_LABELS.CANCELLED,
}

/** What happened, in words, with the actor as the subject. */
const TITLES: Record<ActivityTypeName, string> = {
  submitted: 'submitted the request',
  coordinator_assigned: 'assigned an Event Coordinator',
  coordinator_reassigned: 'reassigned the Event Coordinator',
  clarification_requested: 'asked for clarification',
  clarification_responded: 'responded and resubmitted',
  approved: 'approved the request. Planning has started',
  rejected: 'rejected the request',
  venue_booking_requested: 'requested a venue',
  venue_hold_created: 'placed a tentative venue hold',
  venue_hold_released: 'released a tentative venue hold',
  venue_hold_expired: 'automatically expired a tentative venue hold',
  venue_hold_converted: 'converted a tentative hold into an approved booking',
  venue_booking_cancelled: 'cancelled a venue booking request',
  venue_booking_replaced: 'requested a replacement venue',
  venue_added: 'added another venue',
  venue_booking_approved: 'approved a venue booking',
  venue_booking_rejected: 'rejected a venue booking',
  venue_alternative_suggested: 'suggested an alternative',
  venue_unavailable: 'marked a booked venue as unavailable',
  equipment_requested: 'requested equipment',
  equipment_approved: 'approved the equipment request',
  equipment_rejected: 'rejected the equipment request',
  sent_for_safety_review: 'sent the event for its Operational Safety Check',
  safety_review_withdrawn: 'withdrew the event from safety review',
  safety_approved: 'approved the Operational Safety Check',
  safety_changes_requested: 'asked for safety changes',
  safety_rejected: 'rejected the safety arrangement',
  returned_to_planning: 'returned the event to Planning',
  event_confirmed: 'confirmed the event',
  event_completed: 'recorded that the event took place',
  event_reassignment_requested: 'requested event reassignment',
  event_reassignment_approved: 'approved event reassignment',
  event_reassignment_declined: 'declined event reassignment',
}

/** Venue entries move through booking statuses, not request statuses. */
const BOOKING_STATUS_LABELS: Record<string, string> = {
  pending: 'Pending venue review',
  confirmed: 'Confirmed',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}

function statusLabel(entry: ActivityDto, status: string) {
  const labels = entry.type.startsWith('venue_booking') ? BOOKING_STATUS_LABELS : STATUS_LABELS
  return labels[status] ?? status
}

/** The values captured by the clarification this response answers. */
function askedValues(entries: ActivityDto[], index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    if (entries[i].type === 'clarification_requested') return entries[i].fieldValues
  }
  return null
}

/** Entries whose message is a conversation turn, shown as a speech bubble. */
const CONVERSATIONAL: ActivityTypeName[] = ['clarification_requested', 'clarification_responded', 'rejected',
  'safety_changes_requested', 'safety_rejected', 'safety_review_withdrawn', 'returned_to_planning',
  'venue_booking_rejected', 'equipment_rejected']

function exactTime(iso: string) {
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(new Date(iso))
}

/**
 * The request's audit trail and change log (EC01/EC02/EO26, EC-NEW5), oldest
 * first: who did what, when, what they said, what changed and how the status
 * moved, each labelled Normal or Major. The server decides which entries a
 * role may see and which are Major; this only renders them. Read-only by
 * design: entries are append-only in the database, and nothing here edits,
 * deletes or reverts one.
 *
 * {@code controls} adds EC-NEW5's tools for a long log: a Major-only filter
 * and a jump to the latest entry.
 */
export function ActivityTimeline({ entries, emptyText = 'Nothing has happened yet.', controls = false }: {
  entries: ActivityDto[]
  emptyText?: string
  controls?: boolean
}) {
  const [majorOnly, setMajorOnly] = useState(false)
  if (entries.length === 0) {
    return <p className="field-hint">{emptyText}</p>
  }
  const majorCount = entries.filter((e) => e.significance === 'major').length
  const shown = majorOnly ? entries.filter((e) => e.significance === 'major') : entries
  // Requests submitted before the log existed (V13) have no "submitted" entry.
  const legacy = controls && !entries.some((e) => e.type === 'submitted')
  return (
    <>
      {controls ? (
        <div className="activity-timeline__toolbar">
          <div className="activity-timeline__filter" role="group" aria-label="Filter the change log">
            <button type="button" className="chip" aria-pressed={!majorOnly} onClick={() => setMajorOnly(false)}>
              All changes <span className="status-filter__count">{entries.length}</span>
            </button>
            <button type="button" className="chip" aria-pressed={majorOnly} onClick={() => setMajorOnly(true)}>
              Major only <span className="status-filter__count">{majorCount}</span>
            </button>
          </div>
          <a className="activity-timeline__jump" href="#latest-change">Jump to latest</a>
        </div>
      ) : null}
      {legacy ? (
        <p className="activity-timeline__legacy" role="note">
          Earlier changes were not recorded: this request was submitted before the change log existed.
        </p>
      ) : null}
      {shown.length === 0 ? (
        <p className="field-hint">No major changes recorded.{' '}
          <button type="button" className="link-button" onClick={() => setMajorOnly(false)}>Show all changes</button>
        </p>
      ) : (
    <ol className="activity-timeline" aria-label="Request timeline">
      {shown.map((entry, shownIndex) => {
        const index = entries.indexOf(entry)
        const latest = shownIndex === shown.length - 1
        const questions = entry.fieldQuestions ? Object.entries(entry.fieldQuestions) : []
        const changes = entry.type === 'clarification_responded'
          ? changedFields(askedValues(entries, index), entry.fieldValues) : []
        return (
        <li key={entry.activityId ?? `${entry.type}-${entry.occurredAt}`} className="activity-timeline__entry"
          data-type={entry.type} data-significance={entry.significance ?? 'normal'}
          id={controls && latest ? 'latest-change' : undefined}
          data-side={entry.actorRole === 'eo' ? 'organiser' : 'internal'}>
          <span className="activity-timeline__dot" aria-hidden="true" />
          <div className="activity-timeline__body">
            <p className="activity-timeline__title">
              <strong>{entry.actorName}</strong>{' '}
              {entry.actorRole ? (
                <span className="activity-timeline__role">({ROLE_LABELS[entry.actorRole] ?? entry.actorRole})</span>
              ) : null}{' '}
              {TITLES[entry.type] ?? entry.type}
              {entry.significance === 'major' ? (
                <span className="activity-timeline__major" title="May affect arrangements already made">Major</span>
              ) : null}
            </p>
            <p className="activity-timeline__time">
              <time dateTime={entry.occurredAt} title={exactTime(entry.occurredAt)}>
                {formatRelativeTime(entry.occurredAt)}
              </time>
              <span aria-hidden="true"> · </span>
              <span>{exactTime(entry.occurredAt)} (Singapore time)</span>
            </p>
            {questions.length > 0 ? (
              <dl className="activity-timeline__questions" aria-label="Questions by field">
                {questions.map(([field, question]) => (
                  <div key={field}>
                    <dt>{FIELD_LABELS[field] ?? field}</dt>
                    <dd>{question}</dd>
                  </div>
                ))}
              </dl>
            ) : entry.message ? (
              CONVERSATIONAL.includes(entry.type)
                ? <blockquote className="activity-timeline__message">{entry.message}</blockquote>
                : <p className="activity-timeline__note">{entry.message}</p>
            ) : null}
            {changes.length > 0 ? (
              <ul className="activity-timeline__changes" aria-label="What changed">
                {changes.map((c) => (
                  <li key={c.field}>
                    <strong>{c.label}</strong>: <del>{c.before}</del> <span aria-label="changed to">→</span> <ins>{c.after}</ins>
                  </li>
                ))}
              </ul>
            ) : null}
            {entry.flaggedFields.length > 0 && questions.length === 0 ? (
              <ul className="activity-timeline__flags" aria-label="Fields that need attention">
                {entry.flaggedFields.map((field) => (
                  <li key={field}>{FIELD_LABELS[field] ?? field}</li>
                ))}
              </ul>
            ) : null}
            {entry.fromStatus && entry.toStatus ? (
              <p className="activity-timeline__status">
                {statusLabel(entry, entry.fromStatus)}
                <span aria-label="changed to"> → </span>
                {statusLabel(entry, entry.toStatus)}
              </p>
            ) : null}
          </div>
        </li>
        )
      })}
    </ol>
      )}
    </>
  )
}
