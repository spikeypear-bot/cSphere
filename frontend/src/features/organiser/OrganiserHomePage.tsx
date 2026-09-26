import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { StatusTimeline } from '../../components/ui/StatusTimeline'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ActivityDto } from '../../types/activity'
import type { EventRequestDto } from '../../types/eventRequest'
import { getCompletionPercent } from './eventRequestCompletion'
import { organiserExtraFeatures } from './organiserExtraFeatures'
import './OrganiserHomePage.css'

/** EO15: "view my event requests and their statuses". The scoping happens on
 * the server, from the access token's `organisation` claim (D20) — the value
 * read here is for display only, and nothing is sent with the request. */
export function OrganiserHomePage() {
  const { organisation } = useSession()
  const [requests, setRequests] = useState<EventRequestDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const location = useLocation()
  const navigate = useNavigate()
  // EO02: show the "submitted successfully" confirmation exactly once, then
  // drop it from history state so refreshing or navigating back here later
  // doesn't re-show it.
  const [justSubmitted] = useState(() => Boolean((location.state as { justSubmitted?: boolean } | null)?.justSubmitted))
  const [justResubmitted] = useState(() =>
    Boolean((location.state as { justResubmitted?: boolean } | null)?.justResubmitted))
  useEffect(() => {
    if (justSubmitted || justResubmitted) navigate('.', { replace: true, state: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    <div className="organiser-home">
      <div className="organiser-home__header">
        <div>
          <h1>Your event requests</h1>
          <p className="field-hint">{organisation}</p>
        </div>
        <NewRequestButton />
      </div>

      {justSubmitted ? (
        <p className="organiser-home__confirmation" role="status">
          Your event request has been submitted successfully.
        </p>
      ) : null}

      {justResubmitted ? (
        <p className="organiser-home__confirmation" role="status">
          Your updated request has been sent back to your Event Coordinator.
        </p>
      ) : null}

      {error ? <p role="alert">{error}</p> : null}

      {requests === null && !error ? <p>Loading…</p> : null}

      {requests?.length === 0 ? (
        <Card className="organiser-home__empty">
          <p>No event requests yet — start your first one below.</p>
        </Card>
      ) : null}

      <ul className="organiser-home__list">
        {requests?.map((request) => (
          <li key={request.requestId}>
            <Card className="organiser-home__row"
              data-attention={request.status === 'clarification_required' ? 'true' : undefined}>
              <div className="organiser-home__row-main">
                <div className="organiser-home__row-heading">
                  {request.status === 'draft' ? (
                    <span
                      className="organiser-home__completion-ring"
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
                <StatusTimeline status={request.status} />
                {request.status === 'clarification_required' ? <LatestQuestion requestId={request.requestId} /> : null}
              </div>
              <StatusBadge status={request.status} />
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
              {request.status === 'approved' && request.eventId ? (
                <Link className="button button--secondary" to={`/organiser/events/${request.eventId}`}>
                  View event
                </Link>
              ) : null}
            </Card>
          </li>
        ))}
      </ul>

      <div className="organiser-home__more">
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
