import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RequestByline } from '../../components/ui/RequestByline'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { ACCESSIBILITY_LABELS, FIELD_LABELS, type EventRequestDto } from '../../types/eventRequest'
import { venueFacilityLabels } from '../../types/venue'
import type { EventRequestReview } from '../coordinator/RequestReviewPage'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './IncomingRequestReviewPage.css'

const QUEUE_PATH = '/coordinator-lead/unassigned-requests'

/** The organiser's submitted details, in the order the page lists them. */
const DETAILS: { label: string; value: (request: EventRequestDto) => string | null }[] = [
  { label: FIELD_LABELS.eventName, value: (r) => r.eventName?.trim() || null },
  { label: FIELD_LABELS.purpose, value: (r) => r.purpose?.trim() || null },
  { label: FIELD_LABELS.description, value: (r) => r.description?.trim() || null },
  { label: FIELD_LABELS.startDatetime, value: (r) => (r.startDatetime ? formatEventDateTime(r.startDatetime) : null) },
  { label: FIELD_LABELS.endDatetime, value: (r) => (r.endDatetime ? formatEventDateTime(r.endDatetime) : null) },
  {
    label: FIELD_LABELS.expectedAttendance,
    value: (r) => (r.expectedAttendance == null ? null : `${r.expectedAttendance.toLocaleString()} people`),
  },
  { label: FIELD_LABELS.venueRequirements, value: (r) => r.venueRequirements?.trim() || null },
  { label: FIELD_LABELS.equipmentRequirements, value: (r) => r.equipmentRequirements?.trim() || null },
  {
    label: FIELD_LABELS.accessibilityNeeds,
    value: (r) => r.accessibilityNeeds.map((need) => ACCESSIBILITY_LABELS[need]).join(', ') || null,
  },
  {
    label: 'Required facilities',
    value: (r) => (r.requiredFacilities ?? []).map((facility) => venueFacilityLabels[facility]).join(', ') || null,
  },
  {
    label: FIELD_LABELS.registrationNeeds,
    value: (r) => (r.registrationNeeds == null ? null : r.registrationNeeds ? 'Registration needed' : 'No registration'),
  },
]

/** Why the Lead can no longer act on this request, or null while it is still
 * submitted with nobody assigned. */
function closedReason(request: EventRequestDto): string | null {
  switch (request.status) {
    case 'pending':
      return request.coordinatorId
        ? 'This request has been assigned to an Event Coordinator, who now reviews it.'
        : null
    case 'clarification_required':
      return 'Waiting for the organiser to answer a clarification. It returns to the unassigned list when they resubmit.'
    case 'approved':
      return 'This request has been approved and is now an event in planning.'
    case 'rejected':
      return 'This request has been rejected.'
    case 'cancelled':
      return 'This request has been cancelled.'
    default:
      return null
  }
}

/**
 * ECL-C3: an incoming request as the organiser submitted it, with its
 * history, for the Lead to look over before anyone is assigned.
 */
export function IncomingRequestReviewPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const [review, setReview] = useState<EventRequestReview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    if (!requestId) return
    let cancelled = false
    apiClient.get<EventRequestReview>(`/event-requests/unassigned/${requestId}`)
      .then((result) => {
        if (cancelled) return
        setReview(result)
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(err instanceof ApiClientError
          ? err.status === 404 ? 'Request not found.' : err.message
          : 'Could not load this request.')
      })
    return () => {
      cancelled = true
    }
  }, [requestId])

  if (loadError) {
    return (
      <div className="page page--compact">
        <Link to={QUEUE_PATH}>Back to unassigned requests</Link>
        <p role="alert">{loadError}</p>
      </div>
    )
  }
  if (!review) return <p role="status">Loading request…</p>

  const { request, timeline } = review
  const closed = closedReason(request)

  return (
    <div className="page page--compact">
      <Link to={QUEUE_PATH}>Back to unassigned requests</Link>

      <PageHeader
        title={request.eventName?.trim() || 'Untitled request'}
        description={
          <RequestByline organisation={request.organisation} createdAt={request.createdAt}
            createdByName={request.createdByName}
            updatedAt={request.updatedAt} timeline={timeline} />
        }
        actions={<StatusBadge status={request.status} />}
      />

      <div className="split-layout">
        <div className="lead-review__main">
          {closed ? (
            <Card className="lead-review__closed">
              <p>{closed}</p>
              {request.status === 'rejected' && request.rejectionReason
                ? <blockquote>{request.rejectionReason}</blockquote> : null}
            </Card>
          ) : null}

          <Card className="lead-review__details">
            <h2>Submitted details</h2>
            <dl>
              {DETAILS.map(({ label, value }) => (
                <div key={label} className="lead-review__row">
                  <dt>{label}</dt>
                  <dd>{value(request) ?? <span className="lead-review__empty">Not provided</span>}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </div>

        <aside className="lead-review__history split-layout__sticky" aria-labelledby="lead-review-history">
          <h2 id="lead-review-history">History</h2>
          <ActivityTimeline entries={timeline} />
        </aside>
      </div>
    </div>
  )
}
