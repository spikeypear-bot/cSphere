import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventReassignmentRequestDto } from '../../types/eventReassignment'
import { formatEventDateTime, formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { useVenueRead } from '../venueStaff/useVenueRead'
import '../coordinator/EventReassignmentRequestPage.css'
import './EventReassignmentRequestsPage.css'

const MAX_REASON_LENGTH = 2000

interface CoordinatorOption {
  userId: string
  username: string
}

export function EventReassignmentRequestsPage() {
  const requestRead = useVenueRead<EventReassignmentRequestDto[]>('/event-reassignment-requests')
  const coordinatorRead = useVenueRead<CoordinatorOption[]>('/event-reassignment-requests/coordinators')
  const requests = requestRead.data
  const coordinators = coordinatorRead.data ?? []
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [declineReasons, setDeclineReasons] = useState<Record<string, string>>({})
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const loading = requestRead.loading || coordinatorRead.loading
  const error = actionError ?? requestRead.error ?? coordinatorRead.error

  async function decide(request: EventReassignmentRequestDto, decision: 'approve' | 'decline') {
    const coordinatorUserId = selected[request.reassignmentRequestId]
    const reason = declineReasons[request.reassignmentRequestId]?.trim() ?? ''
    if (decision === 'approve' && !coordinatorUserId) return
    if (decision === 'decline' && !reason) return
    setBusyId(request.reassignmentRequestId)
    setActionError(null)
    setNotice(null)
    try {
      await apiClient.post(
        `/event-reassignment-requests/${request.reassignmentRequestId}/${decision}`,
        decision === 'approve' ? { coordinatorUserId } : { reason },
      )
      setNotice(decision === 'approve'
        ? `"${request.eventName}" has been reassigned.`
        : `The request for "${request.eventName}" has been declined.`)
      requestRead.retry()
      coordinatorRead.retry()
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not record this decision.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator-lead">Dashboard</Link>}
        title="Event reassignment requests"
        description="Review Coordinator requests to transfer their active events."
        actions={<Button variant="ghost" onClick={() => {
          requestRead.retry()
          coordinatorRead.retry()
        }} disabled={loading}>Refresh</Button>}
      />
      {notice ? <Notice>{notice}</Notice> : null}
      {error ? <Notice tone="danger">{error}</Notice> : null}
      {loading && requests === null ? <p role="status">Loading reassignment requests…</p> : null}
      {requests && requests.length === 0 ? (
        <Card className="reassignment-empty"><p>No event reassignment requests are waiting for review.</p></Card>
      ) : null}
      {requests && requests.length > 0 ? (
        <ul className="event-reassignment-queue">
          {requests.map((request) => {
            const requestId = request.reassignmentRequestId
            const selectedCoordinator = selected[requestId] ?? ''
            const declineReason = declineReasons[requestId] ?? ''
            const busy = busyId === requestId
            return (
              <li key={requestId}>
                <Card className="dash-row-card reassignment-request">
                  <div className="event-reassignment-queue__heading">
                    <h2>{request.eventName}</h2>
                    <span className="status-badge status-badge--pending">Pending</span>
                  </div>
                  <dl className="dash-figures dash-facts">
                    <div><dt>Organisation</dt><dd>{request.organisation ?? '—'}</dd></div>
                    <div><dt>Requested by</dt><dd>{request.requesterName}</dd></div>
                    <div><dt>Current coordinator</dt><dd>{request.coordinatorName ?? request.requesterName}</dd></div>
                    <div><dt>Submitted</dt><dd>{formatEventDateTime(request.requestedAt)}</dd></div>
                    <div><dt>Event date</dt><dd>{formatEventTimeRange(request.eventStartDatetime, request.eventEndDatetime)}</dd></div>
                  </dl>
                  <blockquote className="reassignment-quote">
                    <span className="reassignment-quote__label">Reason</span>
                    <p>{request.reason}</p>
                  </blockquote>
                  <div className="event-reassignment-queue__actions">
                    <section className="reassignment-panel">
                      <h3>Approve</h3>
                      <div className="field">
                        <label htmlFor={`coordinator-${requestId}`}>Reassign to</label>
                        <select
                          id={`coordinator-${requestId}`}
                          value={selectedCoordinator}
                          onChange={(e) => setSelected((previous) => ({ ...previous, [requestId]: e.target.value }))}
                        >
                          <option value="">Choose an Event Coordinator</option>
                          {coordinators.filter((coordinator) => coordinator.userId !== request.requesterUserId).map((coordinator) => (
                            <option key={coordinator.userId} value={coordinator.userId}>{coordinator.username}</option>
                          ))}
                        </select>
                      </div>
                      <Button disabled={busy || !selectedCoordinator} onClick={() => void decide(request, 'approve')}>
                        {busy ? 'Saving…' : 'Approve and reassign'}
                      </Button>
                    </section>
                    <section className="reassignment-panel">
                      <h3>Decline</h3>
                      <div className="field">
                        <label htmlFor={`decline-${requestId}`}>Reason for declining</label>
                        <textarea
                          id={`decline-${requestId}`}
                          maxLength={MAX_REASON_LENGTH}
                          value={declineReason}
                          onChange={(e) => setDeclineReasons((previous) => ({ ...previous, [requestId]: e.target.value }))}
                          placeholder="Explain why the current coordinator should keep this event."
                          rows={3}
                        />
                      </div>
                      <Button variant="secondary" disabled={busy || declineReason.trim().length === 0}
                        onClick={() => void decide(request, 'decline')}>
                        Decline request
                      </Button>
                    </section>
                  </div>
                </Card>
              </li>
            )
          })}
        </ul>
      ) : null}
    </div>
  )
}
