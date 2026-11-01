import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { isNotAssigned } from '../../lib/notAssigned'
import { formatRelativeTime } from '../../lib/relativeTime'
import { HOME_BY_ROLE, useSession } from '../../lib/sessionContext'
import { CHANGE_STATUS_LABELS, type ChangeRequestView } from '../../types/changeRequest'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import { changesPathFor, shownChanges } from './changeRequestFields'
import './ChangeRequests.css'

/** "/change-requests/:requestId" (the link in every change-request
 * notification) opens the reader's own console. */
export function ChangeRequestRedirect() {
  const { requestId } = useParams<{ requestId: string }>()
  const { role } = useSession()
  return <Navigate to={role && requestId ? changesPathFor(role, requestId) : '/'} replace />
}

/** "When" for people: the exact time in Singapore and how long ago. */
export function When({ iso }: { iso: string }) {
  return (
    <time dateTime={iso} title={`${formatEventDateTime(iso)} (Singapore time)`}>
      {formatEventDateTime(iso)} (Singapore time) · {formatRelativeTime(iso)}
    </time>
  )
}

export function ChangesTable({ view }: { view: ChangeRequestView }) {
  return (
    <table className="change-request__diff">
      <thead><tr><th scope="col">Detail</th><th scope="col">Before</th><th scope="col">Asked for</th></tr></thead>
      <tbody>
        {shownChanges(view.changes).map((c) => (
          <tr key={c.field}><th scope="row">{c.label}</th><td>{c.before}</td><td><strong>{c.after}</strong></td></tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * EO03/EC-NEW3: a request's change requests, newest first, for the
 * organiser (with Withdraw), its coordinator and the Lead (with Review).
 */
export function ChangeRequestsPage() {
  const { requestId } = useParams<{ requestId: string }>()
  const { role } = useSession()
  const location = useLocation()
  const navigate = useNavigate()
  const [views, setViews] = useState<ChangeRequestView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notAssigned, setNotAssigned] = useState(false)
  const [denied, setDenied] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [justAsked] = useState(() => Boolean((location.state as { justAsked?: boolean } | null)?.justAsked))
  const organiser = role === 'organiser'
  const base = role ? HOME_BY_ROLE[role] : ''

  useEffect(() => {
    if (justAsked) navigate('.', { replace: true, state: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    let cancelled = false
    apiClient.get<ChangeRequestView[]>(`/change-requests?requestId=${requestId}`)
      .then((result) => {
        if (!cancelled) {
          setViews(result)
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (isNotAssigned(err)) setNotAssigned(true)
        // EO03 AC1: someone else's request; the server says "not found" either way.
        else if (err instanceof ApiClientError && (err.status === 403 || err.status === 404)) setDenied(true)
        setError(err instanceof ApiClientError ? err.message : 'The change requests could not be loaded.')
      })
    return () => { cancelled = true }
  }, [requestId, attempt])

  async function withdraw(id: string) {
    setActionError(null)
    try {
      await apiClient.post(`/change-requests/${id}/withdraw`)
      setConfirming(null)
      setAttempt((n) => n + 1)
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'It could not be withdrawn. Please try again.')
      setAttempt((n) => n + 1)
    }
  }

  if (notAssigned) return <NotAssignedNotice what="request" />
  if (denied) {
    return (
      <div className="page">
        <div role="alert" className="change-request__error">
          <p>You can't view these change requests. The event belongs to another organisation, or it no longer exists.</p>
          <Link className="button button--secondary" to={base || '/'}>Back to your console</Link>
        </div>
      </div>
    )
  }
  const name = views?.[0]?.eventName
  const hasPending = views?.some((v) => v.status === 'pending' && !v.closedReason)

  return (
    <div className="page change-request">
      <PageHeader
        eyebrow={<Link to={base}>Back to your console</Link>}
        title="Change requests"
        description={name}
        actions={organiser && views && !hasPending ? (
          <Link className="button button--primary" to={`/organiser/requests/${requestId}/change`}>Request a change</Link>
        ) : undefined}
      />
      {justAsked ? (
        <Notice>Change request sent. Your coordinator has been told; nothing changes until they approve it.</Notice>
      ) : null}
      {actionError ? <Notice tone="danger">{actionError}</Notice> : null}
      {error ? (
        <div role="alert" className="change-request__error">
          <p>The change requests could not be loaded.</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      ) : null}
      {!views && !error ? <p role="status">Loading change requests…</p> : null}
      {views?.length === 0 ? <p className="field-hint">No changes have been requested for this event.</p> : null}

      <ul className="plain-list change-request__list">
        {views?.map((v) => (
          <li key={v.changeRequestId}>
            <Card className="change-request__card" data-status={v.status}>
              <div className="change-request__head">
                <span className={`change-request__status change-request__status--${v.status}`}>
                  {v.closedReason ? 'Closed' : CHANGE_STATUS_LABELS[v.status]}
                </span>
                <span className="field-hint">Asked by {v.requestedByName}, <When iso={v.requestedAt} /></span>
              </div>
              <ChangesTable view={v} />
              <p className="change-request__reason"><strong>Reason:</strong> {v.reason}</p>
              {v.closedReason ? <Notice tone="info">{v.closedReason}</Notice> : null}
              {v.decidedAt && v.status !== 'pending' ? (
                <p className="change-request__decision">
                  {v.status === 'withdrawn' ? 'Withdrawn' : `${CHANGE_STATUS_LABELS[v.status]} by ${v.decidedByName}`}, <When iso={v.decidedAt} />
                  {v.decisionReason ? <><br /><strong>Reason:</strong> {v.decisionReason}</> : null}
                </p>
              ) : null}
              {v.canWithdraw && !v.closedReason ? (
                confirming === v.changeRequestId ? (
                  <div className="change-request__actions" role="group" aria-label="Confirm withdrawal">
                    <span>Withdraw this change request? Your event stays as it is.</span>
                    <Button variant="secondary" onClick={() => setConfirming(null)}>Keep it</Button>
                    <Button onClick={() => void withdraw(v.changeRequestId)}>Withdraw</Button>
                  </div>
                ) : (
                  <Button variant="secondary" onClick={() => setConfirming(v.changeRequestId)}>Withdraw…</Button>
                )
              ) : null}
              {!organiser && v.status === 'pending' ? (
                <Link className="button button--primary" to={`${base}/change-requests/${v.changeRequestId}`}>
                  {v.canDecide ? 'Review and decide' : 'View impact'}
                </Link>
              ) : null}
            </Card>
          </li>
        ))}
      </ul>
    </div>
  )
}
