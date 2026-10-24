import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient } from '../../lib/apiClient'
import { HOME_BY_ROLE, useSession } from '../../lib/sessionContext'
import type { ChangeRequestView } from '../../types/changeRequest'
import { ImpactList } from './ChangeRequestReviewPage'
import { When } from './ChangeRequestsPage'
import './ChangeRequests.css'

const FINISHED = new Set(['COMPLETED', 'CANCELLED', 'REJECTED', 'DRAFT', 'CLARIFICATION_REQUIRED'])

/**
 * EO03/EC-NEW3 on the pages that show an event or a request: the organiser's
 * way in ("Request a change", or the change already waiting), and for the
 * coordinator and Lead the pending change to review and, after an approval,
 * what is still to follow up (EC-NEW3 AC33). Fails quietly with a retry,
 * so the rest of the page keeps working.
 */
export function ChangeRequestPanel({ eventId, requestId, journeyCode }: {
  eventId?: string | null
  requestId?: string | null
  journeyCode?: string | null
}) {
  const { role } = useSession()
  const [views, setViews] = useState<ChangeRequestView[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const query = requestId ? `requestId=${requestId}` : eventId ? `eventId=${eventId}` : null

  useEffect(() => {
    if (!query) return
    let cancelled = false
    apiClient.get<ChangeRequestView[]>(`/change-requests?${query}`)
      .then((result) => {
        if (cancelled) return
        // Anything but a list is treated as a failed load.
        setViews(Array.isArray(result) ? result : null)
        setFailed(!Array.isArray(result))
      })
      .catch(() => { if (!cancelled) setFailed(true) })
    return () => { cancelled = true }
  }, [query, attempt])

  if (!query || !role) return null
  if (failed) {
    return (
      <p className="field-hint change-request__panel-error">
        Change requests could not be loaded.{' '}
        <Button variant="ghost" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
      </p>
    )
  }
  if (!views) return null

  const base = HOME_BY_ROLE[role]
  const pending = views.find((v) => v.status === 'pending' && !v.closedReason)
  const lastApproved = views.find((v) => v.status === 'approved')
  const reqId = views[0]?.requestId ?? requestId
  const historyLink = reqId ? `${base}/requests/${reqId}/changes` : null

  if (role === 'organiser') {
    if (pending) {
      return (
        <Card className="change-request__panel" role="region" aria-label="Change requested">
          <p><span className="change-request__status change-request__status--pending">Change requested</span>{' '}
            You asked to change this event (<When iso={pending.requestedAt} />). It is waiting for your coordinator's
            decision; until then everything stays as shown.</p>
          {historyLink ? <Link className="button button--secondary" to={historyLink}>View your change request</Link> : null}
        </Card>
      )
    }
    if (FINISHED.has(journeyCode ?? '')) return null
    const changeLink = requestId ? `/organiser/requests/${requestId}/change` : `/organiser/events/${eventId}/change`
    return (
      <div className="change-request__panel-actions">
        <Link className="button button--secondary" to={changeLink}>Request a change</Link>
        {views.length > 0 && historyLink ? <Link to={historyLink}>Earlier change requests</Link> : null}
      </div>
    )
  }

  return (
    <>
      {pending ? (
        <Card className="change-request__panel" role="region" aria-label="Change requested">
          <p><span className="change-request__status change-request__status--pending">Change requested</span>{' '}
            {pending.requestedByName} asked to change: {pending.changes.map((c) => c.label).join(', ')}.</p>
          <Link className="button button--primary" to={`${base}/change-requests/${pending.changeRequestId}`}>
            {pending.canDecide ? 'Review and decide' : 'View the change'}
          </Link>
        </Card>
      ) : null}
      {!pending && lastApproved && lastApproved.followUps.length > 0 ? (
        <Card className="change-request__panel" role="region" aria-label="Still to follow up">
          <h2>Still to follow up</h2>
          <p className="field-hint">From the change approved <When iso={lastApproved.decidedAt ?? lastApproved.requestedAt} />.</p>
          <ImpactList items={lastApproved.followUps} />
        </Card>
      ) : null}
    </>
  )
}
