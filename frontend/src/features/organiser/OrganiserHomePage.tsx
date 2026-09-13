import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { EventRequestDto } from '../../types/eventRequest'
import { organiserExtraFeatures } from './organiserExtraFeatures'
import './OrganiserHomePage.css'

/** EO15: "view my event requests and their statuses" — scoped to the
 * organisation entered on the role-select screen (see docs/decision-log.md
 * D6a). */
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
  useEffect(() => {
    if (justSubmitted) navigate('.', { replace: true, state: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!organisation) return
    let cancelled = false
    apiClient
      .get<EventRequestDto[]>('/event-requests', organisation)
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
  }, [organisation])

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
            <Card className="organiser-home__row">
              <div>
                <h3>{request.eventName || 'Untitled draft'}</h3>
                <p className="field-hint">
                  {request.status === 'draft'
                    ? 'Not submitted yet'
                    : new Date(request.createdAt).toLocaleDateString()}
                </p>
              </div>
              <StatusBadge status={request.status} />
              {request.status === 'draft' ? (
                <Link className="button button--secondary" to={`/organiser/requests/${request.requestId}`}>
                  Continue editing
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

function NewRequestButton() {
  return (
    <Link to="/organiser/requests/new" className="button button--primary organiser-home__new">
      New event request
    </Link>
  )
}
