import { useCallback, useEffect, useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { EventRequestDto } from '../../types/eventRequest'
import './ReviewQueuePage.css'

/**
 * EC01/EC02 minimal slice, built alongside EO09/EO19 so those two stories'
 * notifications have a real transition to fire from (see docs/decision-log.md).
 * Every organisation's pending requests, oldest first — Coordinators are
 * internal staff, not scoped to one client's organisation (SecurityConfig's
 * own reasoning).
 */
export function ReviewQueuePage() {
  const { userId } = useSession()
  const [queue, setQueue] = useState<EventRequestDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<Record<string, string>>({})
  const [rejectingId, setRejectingId] = useState<string | null>(null)
  const [reasonDraft, setReasonDraft] = useState('')

  const load = useCallback(async () => {
    try {
      const results = await apiClient.get<EventRequestDto[]>('/event-requests/queue')
      setQueue(results)
      setError(null)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not load the review queue.')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function runAction(id: string, action: () => Promise<unknown>) {
    setBusyId(id)
    setActionError((prev) => ({ ...prev, [id]: '' }))
    try {
      await action()
      await load()
      setRejectingId(null)
      setReasonDraft('')
    } catch (err) {
      setActionError((prev) => ({
        ...prev,
        [id]: err instanceof ApiClientError ? err.message : 'That action failed.',
      }))
    } finally {
      setBusyId(null)
    }
  }

  function assignToSelf(request: EventRequestDto) {
    void runAction(request.requestId, () =>
      apiClient.post(`/event-requests/${request.requestId}/assign-coordinator`, {
        coordinatorUserId: userId,
      }),
    )
  }

  function approve(request: EventRequestDto) {
    void runAction(request.requestId, () =>
      apiClient.post(`/event-requests/${request.requestId}/approve`),
    )
  }

  function reject(request: EventRequestDto) {
    if (!reasonDraft.trim()) return
    void runAction(request.requestId, () =>
      apiClient.post(`/event-requests/${request.requestId}/reject`, { reason: reasonDraft.trim() }),
    )
  }

  return (
    <div className="review-queue">
      <h1>Event Request Review</h1>
      <p className="field-hint">
        Every organisation's requests awaiting a decision, oldest first. Assign yourself before
        approving or rejecting.
      </p>

      {error ? <p role="alert">{error}</p> : null}
      {queue === null && !error ? <p>Loading…</p> : null}
      {queue?.length === 0 ? (
        <Card className="review-queue__empty">
          <p>Nothing is waiting for review right now.</p>
        </Card>
      ) : null}

      <ul className="review-queue__list">
        {queue?.map((request) => {
          const assignedToMe = request.coordinatorId === userId
          const assigned = Boolean(request.coordinatorId)
          return (
            <li key={request.requestId}>
              <Card className="review-queue__row">
                <div className="review-queue__summary">
                  <h3>{request.eventName || 'Untitled request'}</h3>
                  <p className="field-hint">{request.organisation}</p>
                  <p className="field-hint">
                    {request.expectedAttendance ?? '—'} attendees ·{' '}
                    {request.startDatetime ? new Date(request.startDatetime).toLocaleDateString() : 'no date given'}
                  </p>
                  <p className="field-hint">
                    {assigned
                      ? assignedToMe
                        ? 'Assigned to you'
                        : 'Assigned to another coordinator'
                      : 'Unassigned'}
                  </p>
                </div>

                <div className="review-queue__actions">
                  {!assignedToMe ? (
                    <Button
                      variant="secondary"
                      disabled={busyId === request.requestId}
                      onClick={() => assignToSelf(request)}
                    >
                      Assign to me
                    </Button>
                  ) : (
                    <>
                      <Button
                        disabled={busyId === request.requestId}
                        onClick={() => approve(request)}
                      >
                        Approve
                      </Button>
                      {rejectingId === request.requestId ? (
                        <div className="review-queue__reject-form">
                          <textarea
                            value={reasonDraft}
                            onChange={(e) => setReasonDraft(e.target.value)}
                            placeholder="Why is this request being rejected?"
                            rows={2}
                          />
                          <div className="review-queue__reject-actions">
                            <Button
                              variant="secondary"
                              onClick={() => {
                                setRejectingId(null)
                                setReasonDraft('')
                              }}
                            >
                              Cancel
                            </Button>
                            <Button
                              disabled={busyId === request.requestId || !reasonDraft.trim()}
                              onClick={() => reject(request)}
                            >
                              Confirm rejection
                            </Button>
                          </div>
                        </div>
                      ) : (
                        <Button variant="secondary" onClick={() => setRejectingId(request.requestId)}>
                          Reject
                        </Button>
                      )}
                    </>
                  )}
                </div>
                {actionError[request.requestId] ? (
                  <p role="alert" className="review-queue__error">
                    {actionError[request.requestId]}
                  </p>
                ) : null}
              </Card>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
