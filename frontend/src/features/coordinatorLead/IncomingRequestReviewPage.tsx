import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { RequestByline } from '../../components/ui/RequestByline'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { ACCESSIBILITY_LABELS, FIELD_LABELS, type EventRequestDto } from '../../types/eventRequest'
import { venueFacilityLabels } from '../../types/venue'
import { MAX_MESSAGE } from '../coordinator/clarificationDraft'
import type { EventRequestReview } from '../coordinator/RequestReviewPage'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './IncomingRequestReviewPage.css'

const QUEUE_PATH = '/coordinator-lead/unassigned-requests'
const ASSIGNMENTS_PATH = '/coordinator-lead/assignments'

type Decision = 'assign' | 'clarify' | 'reject'

/** Mirrors backend CoordinatorDto (ELC-C6). */
interface Coordinator {
  userId: string
  username: string
}

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

/** Why the Lead can no longer act on this request, or null while nobody is
 * assigned and it is submitted or waiting for the organiser's clarification.
 * `assignee` names the assigned coordinator. */
function closedReason(request: EventRequestDto, assignee: string): string | null {
  switch (request.status) {
    case 'pending':
      return request.coordinatorId
        ? `This request has been assigned to ${assignee}, who now reviews it.`
        : null
    case 'clarification_required':
      return request.coordinatorId
        ? `This request has been assigned to ${assignee}, who is waiting for the organiser to answer a clarification.`
        : null
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
 * history, for the Lead to look over before anyone is assigned. While it is
 * still unassigned the Lead can assign it to an Event Coordinator (ELC-C6),
 * reject it or ask the organiser a question; approving stays with the
 * coordinator it is assigned to. While it waits for the organiser's answer
 * it can only be assigned.
 */
export function IncomingRequestReviewPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const navigate = useNavigate()
  // Set by a card on the coordinator assignments page (ECL-C2): the Lead goes
  // back there, and the page can name the coordinator the card showed.
  const from = (useLocation().state as { coordinator?: { id: string; name: string } } | null)?.coordinator
  const back = from
    ? <Link to={ASSIGNMENTS_PATH}>Back to coordinator assignments</Link>
    : <Link to={QUEUE_PATH}>Back to unassigned requests</Link>
  const [review, setReview] = useState<EventRequestReview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [decision, setDecision] = useState<Decision | null>(null)
  const [message, setMessage] = useState('')
  const [reason, setReason] = useState('')
  // Loaded the first time the Lead chooses to assign.
  const [coordinators, setCoordinators] = useState<Coordinator[] | null>(null)
  const [coordinatorId, setCoordinatorId] = useState('')
  const [busy, setBusy] = useState(false)
  const [inputError, setInputError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // Bumped after a refused action so the page shows the request as it is now.
  const [reloadToken, setReloadToken] = useState(0)

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
  }, [requestId, reloadToken])

  if (loadError) {
    return (
      <div className="page page--compact">
        {back}
        <p role="alert">{loadError}</p>
      </div>
    )
  }
  if (!review) return <p role="status">Loading request…</p>

  const { request, timeline } = review
  // Only while the card's coordinator still holds the request.
  const closed = closedReason(request,
    from && from.id === request.coordinatorId ? from.name : 'an Event Coordinator')
  const waiting = request.status === 'clarification_required'
  const name = request.eventName?.trim() || 'Untitled request'

  function choose(next: Decision) {
    setDecision(next)
    setInputError(null)
    setActionError(null)
    if (next === 'assign' && coordinators === null) void loadCoordinators()
  }

  async function loadCoordinators() {
    try {
      setCoordinators(await apiClient.get<Coordinator[]>('/event-requests/unassigned/coordinators'))
    } catch (err) {
      setInputError(err instanceof ApiClientError ? err.message : 'Could not load the Event Coordinators.')
    }
  }

  /** Sends one action, then returns to the queue with its outcome. A refusal
   * (e.g. someone was assigned meanwhile) stays here and reloads the request. */
  async function send(path: string, body: unknown, success: string) {
    setBusy(true)
    setInputError(null)
    setActionError(null)
    try {
      await apiClient.post(`/event-requests/unassigned/${request.requestId}/${path}`, body)
      navigate(QUEUE_PATH, { state: { notice: success } })
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'That action failed. Nothing was changed.')
      setDecision(null)
      setReloadToken((n) => n + 1)
    } finally {
      setBusy(false)
    }
  }

  function sendAssignment() {
    const coordinator = coordinators?.find((c) => c.userId === coordinatorId)
    if (!coordinator) return setInputError('Choose an Event Coordinator before assigning.')
    void send('assign', { coordinatorUserId: coordinator.userId },
      `“${name}” was assigned to ${coordinator.username}. The organiser has been notified.`)
  }

  function sendClarification() {
    const text = message.trim()
    if (!text) return setInputError('Enter a message for the organiser before sending.')
    if (text.length > MAX_MESSAGE) return setInputError(`Messages can be at most ${MAX_MESSAGE} characters.`)
    void send('clarifications', { message: text },
      `Clarification sent for “${name}”. ${request.organisation}'s organisers have been notified.`)
  }

  function sendRejection() {
    const text = reason.trim()
    if (!text) return setInputError('Enter a reason before rejecting.')
    void send('reject', { reason: text }, `“${name}” was rejected. The organiser has been notified.`)
  }

  return (
    <div className="page page--compact">
      {back}

      <PageHeader
        title={name}
        description={
          <RequestByline organisation={request.organisation} createdAt={request.createdAt}
            createdByName={request.createdByName}
            updatedAt={request.updatedAt} timeline={timeline} />
        }
        actions={<StatusBadge status={request.status} />}
      />

      {actionError ? <Notice tone="danger">{actionError}</Notice> : null}

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

          {closed ? null : (
            <Card className="lead-review__decision">
              <h2>Decision</h2>
              <p className="field-hint">
                {waiting
                  ? 'Waiting for the organiser to answer a clarification. It can still be assigned: the coordinator gets it when the organiser resubmits.'
                  : 'Assign the request to an Event Coordinator, or ask the organiser a question or reject it first.'}
              </p>
              <div className="lead-review__choices" role="group" aria-label="Choose an outcome">
                <Button variant={decision === 'assign' ? 'primary' : 'secondary'} onClick={() => choose('assign')}>
                  Assign
                </Button>
                {waiting ? null : (
                  <>
                    <Button variant={decision === 'clarify' ? 'primary' : 'secondary'} onClick={() => choose('clarify')}>
                      Ask for clarification
                    </Button>
                    <Button variant={decision === 'reject' ? 'primary' : 'secondary'} onClick={() => choose('reject')}>
                      Reject
                    </Button>
                  </>
                )}
              </div>

              {decision === 'assign' ? (
                <div className="lead-review__panel">
                  <label htmlFor="lead-coordinator">Event Coordinator</label>
                  <select id="lead-coordinator" value={coordinatorId} disabled={coordinators === null}
                    onChange={(e) => setCoordinatorId(e.target.value)}>
                    <option value="">Choose an Event Coordinator</option>
                    {(coordinators ?? []).map((coordinator) => (
                      <option key={coordinator.userId} value={coordinator.userId}>{coordinator.username}</option>
                    ))}
                  </select>
                  <p className="field-hint">The request keeps its status and details. The coordinator reviews it
                    from their own queue, and the organiser is told who they are.</p>
                  <Button disabled={busy} onClick={sendAssignment}>
                    {busy ? 'Assigning…' : 'Assign request'}
                  </Button>
                </div>
              ) : null}

              {decision === 'clarify' ? (
                <div className="lead-review__panel">
                  <label htmlFor="lead-clarification">What do you need from the organiser?</label>
                  <textarea id="lead-clarification" rows={5} value={message}
                    onChange={(e) => setMessage(e.target.value)} aria-describedby="lead-clarification-count" />
                  <span id="lead-clarification-count"
                    className={message.trim().length > MAX_MESSAGE ? 'field-error' : 'field-hint'}>
                    {message.trim().length}/{MAX_MESSAGE}
                  </span>
                  <p className="field-hint">Their submitted details are kept. The request stays in the unassigned
                    list while it waits, and can still be assigned.</p>
                  <Button disabled={busy} onClick={sendClarification}>
                    {busy ? 'Sending…' : 'Send clarification request'}
                  </Button>
                </div>
              ) : null}

              {decision === 'reject' ? (
                <div className="lead-review__panel">
                  <label htmlFor="lead-rejection">Reason for rejection (shown to the organiser)</label>
                  <textarea id="lead-rejection" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
                  <Button disabled={busy} onClick={sendRejection}>
                    {busy ? 'Rejecting…' : 'Confirm rejection'}
                  </Button>
                </div>
              ) : null}

              {inputError ? <p role="alert" className="error-text lead-review__error">{inputError}</p> : null}
            </Card>
          )}
        </div>

        <aside className="lead-review__history split-layout__sticky" aria-labelledby="lead-review-history">
          <h2 id="lead-review-history">History</h2>
          <ActivityTimeline entries={timeline} />
        </aside>
      </div>
    </div>
  )
}
