import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { StatusTimeline } from '../../components/ui/StatusTimeline'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { RequestByline } from '../../components/ui/RequestByline'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ActivityDto } from '../../types/activity'
import { ACCESSIBILITY_LABELS, FIELD_LABELS, type EventRequestDto } from '../../types/eventRequest'
import { composeClarification, MAX_MESSAGE } from './clarificationDraft'
import './RequestReviewPage.css'

/** Mirrors backend EventRequestReviewDto. */
export interface EventRequestReview {
  request: EventRequestDto
  missingFields: string[]
  scheduleValid: boolean
  timeline: ActivityDto[]
}

type Decision = 'approve' | 'clarify' | 'reject'

const DETAIL_ORDER = [
  'eventName', 'purpose', 'description', 'startDatetime', 'endDatetime', 'expectedAttendance',
  'venueRequirements', 'accessibilityNeeds', 'equipmentRequirements', 'registrationNeeds',
] as const

function formatDateTime(iso: string) {
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(new Date(iso))
}

function displayValue(request: EventRequestDto, field: (typeof DETAIL_ORDER)[number]): string | null {
  switch (field) {
    case 'startDatetime':
    case 'endDatetime':
      return request[field] ? formatDateTime(request[field] as string) : null
    case 'expectedAttendance':
      return request.expectedAttendance == null ? null : `${request.expectedAttendance.toLocaleString()} people`
    case 'accessibilityNeeds':
      return request.accessibilityNeeds.length === 0 ? null
        : request.accessibilityNeeds.map((need) => ACCESSIBILITY_LABELS[need]).join(', ')
    case 'registrationNeeds':
      return request.registrationNeeds == null ? null : request.registrationNeeds ? 'Registration needed' : 'No registration'
    default:
      return (request[field] as string | null)?.trim() || null
  }
}

/**
 * EC02 (review) and EC01 (request clarification) on one screen: every
 * submitted detail with a per-field flag, a readiness check that explains
 * why Approve is unavailable before anyone presses it, the three possible
 * outcomes, and the request's full history. Only the assigned coordinator can
 * load it (the server returns 403 otherwise).
 */
export function RequestReviewPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const navigate = useNavigate()
  const [review, setReview] = useState<EventRequestReview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [flags, setFlags] = useState<string[]>([])
  const [decision, setDecision] = useState<Decision | null>(null)
  // Used only when no field is flagged: a single general question.
  const [message, setMessage] = useState('')
  // One question per flagged field, keyed by field. Kept when a field is
  // unflagged, so re-flagging it by accident does not lose what was typed.
  const [questions, setQuestions] = useState<Record<string, string>>({})
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // Bumped after every action to refetch, the same inline-fetch-effect
  // shape as EventDetailsPage.
  const [reloadToken, setReloadToken] = useState(0)
  const load = () => setReloadToken((n) => n + 1)

  useEffect(() => {
    if (!requestId) return
    let cancelled = false
    apiClient.get<EventRequestReview>(`/event-requests/${requestId}/review`)
      .then((result) => {
        if (cancelled) return
        setReview(result)
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(err instanceof ApiClientError
          ? err.isForbidden ? 'This request is not assigned to you, so you cannot review it.' : err.message
          : 'Could not load this request.')
      })
    return () => {
      cancelled = true
    }
  }, [requestId, reloadToken])

  if (loadError) {
    return (
      <div className="request-review">
        <Link to="/coordinator/review-queue">Back to review queue</Link>
        <p role="alert">{loadError}</p>
      </div>
    )
  }
  if (!review) return <p>Loading…</p>

  const { request, missingFields, scheduleValid, timeline } = review
  const isOpen = request.status === 'pending'
  const waiting = request.status === 'clarification_required'
  const ready = missingFields.length === 0 && scheduleValid
  const lastClarification = [...timeline].reverse().find((e) => e.type === 'clarification_requested')

  function toggleFlag(field: string) {
    setFlags((prev) => (prev.includes(field) ? prev.filter((f) => f !== field) : [...prev, field]))
  }

  function startClarification(withFlags: string[]) {
    const merged = Array.from(new Set([...flags, ...withFlags]))
    setFlags(merged)
    setDecision('clarify')
    setActionError(null)
  }

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setActionError(null)
    try {
      await action()
      setNotice(success)
      setDecision(null)
      setMessage('')
      setQuestions({})
      setReason('')
      setFlags([])
      load()
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'That action failed. Nothing was changed.')
      if (err instanceof ApiClientError && err.missingFields) load()
    } finally {
      setBusy(false)
    }
  }

  async function approve() {
    setBusy(true)
    setActionError(null)
    try {
      const approved = await apiClient.post<EventRequestDto>(`/event-requests/${request.requestId}/approve`)
      navigate(`/coordinator/events/${approved.eventId}`, { state: { justApproved: true } })
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not approve this request.')
      load()
    } finally {
      setBusy(false)
    }
  }

  // With flags, every flagged field needs its own question; without, the
  // single general box is used. Either way one message goes to the server.
  const outgoing = flags.length > 0 ? composeClarification(flags, questions) : message.trim()
  const unanswered = flags.filter((f) => !(questions[f] ?? '').trim())
  const messageLength = outgoing.length
  const messageValid = messageLength > 0 && messageLength <= MAX_MESSAGE && unanswered.length === 0

  return (
    <div className="request-review">
      <Link to="/coordinator/review-queue">Back to review queue</Link>

      <header className="request-review__header">
        <div>
          <h1>{request.eventName || 'Untitled request'}</h1>
          <RequestByline organisation={request.organisation} createdAt={request.createdAt}
            createdByName={request.createdByName}
            updatedAt={request.updatedAt} timeline={timeline} />
        </div>
        <StatusBadge status={request.status} />
      </header>
      <StatusTimeline status={request.status} />

      {notice ? <p className="request-review__notice" role="status">{notice}</p> : null}

      <div className="request-review__layout">
        <div className="request-review__main">
          {isOpen ? (
            <Card className={`request-review__readiness request-review__readiness--${ready ? 'ready' : 'blocked'}`}>
              {ready ? (
                <p><strong>All required details are present.</strong> This request can proceed to planning.</p>
              ) : (
                <>
                  <p><strong>This request cannot proceed to planning yet.</strong></p>
                  <ul>
                    {missingFields.map((f) => <li key={f}>{FIELD_LABELS[f] ?? f} is missing</li>)}
                    {!scheduleValid ? <li>The end date &amp; time is before the start</li> : null}
                  </ul>
                  <Button variant="secondary" onClick={() => startClarification(
                    [...missingFields, ...(scheduleValid ? [] : ['startDatetime', 'endDatetime'])])}>
                    Ask the organiser about these
                  </Button>
                </>
              )}
            </Card>
          ) : null}

          {waiting && lastClarification ? (
            <Card className="request-review__waiting">
              <p><strong>Waiting for the organiser</strong> since {formatRelativeTime(lastClarification.occurredAt)}.
                It returns to your queue when they resubmit.</p>
              <blockquote>{lastClarification.message}</blockquote>
            </Card>
          ) : null}

          <Card className="request-review__details">
            <div className="request-review__details-head">
              <h2>Submitted details</h2>
              {isOpen ? <p className="field-hint">Flag anything missing, unclear or inconsistent.</p> : null}
            </div>
            <dl>
              {DETAIL_ORDER.map((field) => {
                const value = displayValue(request, field)
                const flagged = flags.includes(field)
                const missing = missingFields.includes(field)
                return (
                  <div key={field} className="request-review__row" data-flagged={flagged} data-missing={missing}>
                    <dt>{FIELD_LABELS[field]}</dt>
                    <dd>{value ?? <span className="request-review__missing">{missing ? 'Missing' : 'Not provided'}</span>}</dd>
                    {isOpen ? (
                      <button type="button" className="request-review__flag" aria-pressed={flagged}
                        aria-label={`${flagged ? 'Unflag' : 'Flag'} ${FIELD_LABELS[field]}`}
                        onClick={() => toggleFlag(field)}>
                        {flagged ? 'Flagged' : 'Flag'}
                      </button>
                    ) : null}
                  </div>
                )
              })}
            </dl>
          </Card>

          {isOpen || waiting ? (
            <Card className="request-review__decision">
              <h2>Decision</h2>
              <div className="request-review__choices" role="group" aria-label="Choose an outcome">
                {isOpen ? (
                  <>
                    <Button variant={decision === 'approve' ? 'primary' : 'secondary'} disabled={!ready}
                      title={ready ? undefined : 'Resolve the missing details first'}
                      onClick={() => { setDecision('approve'); setActionError(null) }}>
                      Approve
                    </Button>
                    <Button variant={decision === 'clarify' ? 'primary' : 'secondary'}
                      onClick={() => startClarification([])}>
                      Request clarification{flags.length ? ` (${flags.length})` : ''}
                    </Button>
                  </>
                ) : null}
                <Button variant={decision === 'reject' ? 'primary' : 'secondary'}
                  onClick={() => { setDecision('reject'); setActionError(null) }}>
                  Reject
                </Button>
              </div>

              {decision === 'approve' ? (
                <div className="request-review__panel">
                  <p>Approving creates the event in <strong>Planning</strong> with these details, keeps you as its
                    coordinator, and notifies the organiser.</p>
                  <Button disabled={busy} onClick={() => void approve()}>
                    {busy ? 'Approving…' : 'Approve and start planning'}
                  </Button>
                </div>
              ) : null}

              {decision === 'clarify' ? (
                <div className="request-review__panel">
                  {flags.length > 0 ? (
                    <ul className="request-review__questions" aria-label="Flagged fields">
                      {flags.map((f) => {
                        const label = FIELD_LABELS[f] ?? f
                        return (
                          <li key={f} className="request-review__question">
                            <span className="request-review__pill">
                              {label}
                              <button type="button" aria-label={`Remove ${label}`} onClick={() => toggleFlag(f)}>×</button>
                            </span>
                            <label htmlFor={`clarification-${f}`}>
                              What do you need to know more from the organiser about the <em>{label}</em>?
                            </label>
                            <textarea id={`clarification-${f}`} rows={3} value={questions[f] ?? ''}
                              onChange={(e) => setQuestions((prev) => ({ ...prev, [f]: e.target.value }))} />
                          </li>
                        )
                      })}
                    </ul>
                  ) : (
                    <>
                      <p className="field-hint">Tip: flag fields above to ask about each one separately.</p>
                      <label htmlFor="clarification-message">What do you need from the organiser?</label>
                      <textarea id="clarification-message" rows={5} value={message}
                        onChange={(e) => setMessage(e.target.value)}
                        aria-describedby="clarification-count" />
                    </>
                  )}
                  <span id="clarification-count"
                    className={messageLength > MAX_MESSAGE ? 'field-error' : 'field-hint'}>
                    {messageLength}/{MAX_MESSAGE}
                  </span>
                  <p className="field-hint">Their submitted details are kept. They can edit and resubmit, and the
                    request comes back to you.</p>
                  <Button disabled={busy || !messageValid} onClick={() => void run(
                    () => apiClient.post(`/event-requests/${request.requestId}/clarifications`,
                      { message: outgoing, flaggedFields: flags }),
                    `Clarification sent. ${request.organisation}'s organisers have been notified.`)}>
                    {busy ? 'Sending…' : 'Send clarification request'}
                  </Button>
                </div>
              ) : null}

              {decision === 'reject' ? (
                <div className="request-review__panel">
                  <label htmlFor="rejection-reason">Reason for rejection (shown to the organiser)</label>
                  <textarea id="rejection-reason" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
                  <Button disabled={busy || !reason.trim()} onClick={() => void run(
                    () => apiClient.post(`/event-requests/${request.requestId}/reject`, { reason: reason.trim() }),
                    'Request rejected. The organiser has been notified.')}>
                    {busy ? 'Rejecting…' : 'Confirm rejection'}
                  </Button>
                </div>
              ) : null}

              {actionError ? <p role="alert" className="request-review__error">{actionError}</p> : null}
            </Card>
          ) : null}

          {request.status === 'approved' && request.eventId ? (
            <Card className="request-review__panel">
              <p>This request was approved and is now an event in planning.</p>
              <Link className="button button--primary" to={`/coordinator/events/${request.eventId}`}>Open event</Link>
            </Card>
          ) : null}
        </div>

        <aside className="request-review__history" aria-labelledby="history-heading">
          <h2 id="history-heading">History</h2>
          <ActivityTimeline entries={timeline} />
        </aside>
      </div>
    </div>
  )
}
