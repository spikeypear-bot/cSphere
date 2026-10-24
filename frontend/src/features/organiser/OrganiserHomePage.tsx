import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { StatusTimeline } from '../../components/ui/StatusTimeline'
import { JourneyStepper, StatusFilter } from '../../components/ui/JourneyStatus'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ActivityDto } from '../../types/activity'
import type { EventRequestDto } from '../../types/eventRequest'
import type { ChangeRequestView } from '../../types/changeRequest'
import { getCompletionPercent } from './eventRequestCompletion'
import { organiserExtraFeatures } from './organiserExtraFeatures'
import { CancelRequestAction } from './CancelRequestAction'
import './OrganiserHomePage.css'

/** EO15: "view my event requests and their statuses". The scoping happens on
 * the server, from the access token's `organisation` claim (D20) — the value
 * read here is for display only, and nothing is sent with the request. */
/** EO03 AC2-AC4: Submitted, Under Review, or an event still being planned or confirmed. */
function changeable(request: EventRequestDto): boolean {
  if (request.status !== 'pending' && request.status !== 'approved') return false
  return !['COMPLETED', 'CANCELLED'].includes(request.journeyStatus?.code ?? '')
}

export function OrganiserHomePage() {
  const { organisation } = useSession()
  const [requests, setRequests] = useState<EventRequestDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // EC-NEW2: filter by the same status the coordinator sees.
  const [filter, setFilter] = useState<string | null>(null)
  const location = useLocation()
  const navigate = useNavigate()
  // EO02: show the "submitted successfully" confirmation exactly once, then
  // drop it from history state so refreshing or navigating back here later
  // doesn't re-show it.
  const [justSubmitted] = useState(() => Boolean((location.state as { justSubmitted?: boolean } | null)?.justSubmitted))
  const [justResubmitted] = useState(() =>
    Boolean((location.state as { justResubmitted?: boolean } | null)?.justResubmitted))
  // EO07: the request just cancelled from this list, for the confirmation.
  const [justCancelled, setJustCancelled] = useState<string | null>(null)
  useEffect(() => {
    if (justSubmitted || justResubmitted) navigate('.', { replace: true, state: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // EO03 AC30: which requests have a change waiting (one call for the list).
  const [pendingChanges, setPendingChanges] = useState<Set<string>>(new Set())
  useEffect(() => {
    let cancelled = false
    apiClient.get<ChangeRequestView[]>('/change-requests')
      .then((views) => { if (!cancelled) setPendingChanges(new Set(views.map((v) => v.requestId))) })
      .catch(() => { /* the list still works without the badges */ })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    let cancelled = false
    apiClient
      .get<EventRequestDto[]>('/event-requests')
      .then((results) => {
        if (!cancelled) setRequests(results)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof ApiClientError ? err.message : 'Could not load your event requests.')
      })
    return () => {
      cancelled = true
    }
    // The route guard guarantees a signed-in Organiser here, and the scope is
    // the token's, so there is nothing for this to depend on.
  }, [])

  return (
    <div className="page">
      <PageHeader title="Your event requests" description={organisation} actions={<NewRequestButton />} />

      {justSubmitted ? <Notice>Your event request has been submitted successfully.</Notice> : null}

      {justResubmitted ? (
        <Notice>Your updated request has been sent back for review.</Notice>
      ) : null}

      {justCancelled ? <Notice>"{justCancelled}" has been cancelled.</Notice> : null}

      {error ? <p role="alert">{error}</p> : null}

      {requests === null && !error ? <p>Loading…</p> : null}

      {requests?.length === 0 ? (
        <Card className="organiser-home__empty">
          <p>No event requests yet — start your first one below.</p>
        </Card>
      ) : null}

      <StatusFilter
        statuses={(requests ?? []).flatMap((r) => (r.journeyStatus ? [r.journeyStatus] : []))}
        selected={filter} onChange={setFilter} label="Filter your requests by status" />

      <ul className="plain-list">
        {requests?.filter((r) => filter === null || r.journeyStatus?.code === filter).map((request) => (
          <li key={request.requestId}>
            <Card className="organiser-home__row"
              data-attention={request.status === 'clarification_required' ? 'true' : undefined}>
              <div className="organiser-home__row-main">
                <div className="organiser-home__row-heading">
                  {request.status === 'draft' ? (
                    <span
                      className="completion-ring"
                      style={{ ['--percent' as string]: getCompletionPercent(request) }}
                      title={`${getCompletionPercent(request)}% of required fields complete`}
                      aria-hidden="true"
                    />
                  ) : null}
                  <h3>{request.eventName || 'Untitled draft'}</h3>
                </div>
                <p className="field-hint">
                  {request.status === 'draft'
                    ? `Edited ${formatRelativeTime(request.updatedAt)} — not submitted yet`
                    : `Last updated ${formatRelativeTime(request.updatedAt)}`}
                </p>
                {request.journeyStatus && request.status !== 'draft'
                  ? <JourneyStepper status={request.journeyStatus} />
                  : <StatusTimeline status={request.status} />}
                {request.status === 'clarification_required' ? <LatestQuestion requestId={request.requestId} /> : null}
              </div>
              <StatusBadge status={request.status} journey={request.journeyStatus} />
              {pendingChanges.has(request.requestId) ? (
                <Link className="change-request__status change-request__status--pending"
                  to={`/organiser/requests/${request.requestId}/changes`}>Change requested</Link>
              ) : changeable(request) ? (
                <Link className="button button--ghost" to={`/organiser/requests/${request.requestId}/change`}>
                  Request a change
                </Link>
              ) : null}
              {request.status === 'draft' ? (
                <Link className="button button--secondary" to={`/organiser/requests/${request.requestId}`}>
                  Continue editing
                </Link>
              ) : null}
              {request.status === 'clarification_required' ? (
                <Link className="button button--primary" to={`/organiser/requests/${request.requestId}/respond`}>
                  Respond
                </Link>
              ) : null}
              {request.status === 'pending' || request.status === 'clarification_required' ? (
                <CancelRequestAction requestId={request.requestId} eventName={request.eventName || 'Untitled request'}
                  onCancelled={(updated) => {
                    setRequests((current) => current?.map((r) => r.requestId === updated.requestId ? updated : r) ?? null)
                    setJustCancelled(updated.eventName || 'Your request')
                  }} />
              ) : null}
              {request.status === 'approved' && request.eventId ? (
                <Link className="button button--secondary" to={`/organiser/events/${request.eventId}`}>
                  View event
                </Link>
              ) : null}
            </Card>
          </li>
        ))}
      </ul>

      <div className="page-section--divided">
        <h2>More Event Organiser stories</h2>
        <p className="field-hint">Not built yet — pick one up next sprint.</p>
        <SkeletonFeatureGrid basePath="/organiser" features={organiserExtraFeatures} />
      </div>
    </div>
  )
}

/** EO26: the coordinator's latest question, right on the list, so the
 * organiser knows what is being asked before opening the request. */
function LatestQuestion({ requestId }: { requestId: string }) {
  const [question, setQuestion] = useState<ActivityDto | null>(null)
  useEffect(() => {
    let cancelled = false
    apiClient.get<ActivityDto[]>(`/event-requests/${requestId}/timeline`)
      .then((entries) => {
        if (cancelled || !Array.isArray(entries)) return
        setQuestion([...entries].reverse().find((e) => e.type === 'clarification_requested') ?? null)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [requestId])
  if (!question?.message) return null
  return (
    <p className="organiser-home__question">
      <strong>{question.actorName} asked:</strong> {question.message}
    </p>
  )
}

function NewRequestButton() {
  return (
    <Link to="/organiser/requests/new" className="button button--primary organiser-home__new">
      New event request
    </Link>
  )
}
