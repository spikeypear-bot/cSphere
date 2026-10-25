import { useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import '../technicalSupport.css'
import type {
  EquipmentRequestDecision,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
} from './reservation.types'
import { updateEquipmentRequestStatus } from './reservationApi'
import { everyLineReserved, reservedForEventPeriod } from './reservationUtils'

/** TS06: reserved-against-requested coverage, then approve (only once every
 * line is reserved for the whole event) or reject (with a reason).
 * `ready` is false while the lines and reservations are (re)loading. */
export function DecisionSection({ request, lines, reservations, ready, onDecided }: {
  request: EquipmentRequestSummary
  lines: RequestLine[]
  reservations: EquipmentReservation[]
  ready: boolean
  onDecided: (decision: EquipmentRequestDecision) => void
}) {
  const [decision, setDecision] = useState<'approved' | 'rejected'>('approved')
  const [rejectReason, setRejectReason] = useState('')
  const [updatingStatus, setUpdatingStatus] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const allItemsReserved = everyLineReserved(lines, reservations, request.eventStart, request.eventEnd)

  async function handleUpdateStatus() {
    if (!ready) return
    if (decision === 'approved' && !allItemsReserved) return
    if (decision === 'rejected' && rejectReason.trim() === '') return

    setUpdatingStatus(true)
    setMessage(null)
    setError(null)
    try {
      const updated = await updateEquipmentRequestStatus(
        request.requestId,
        decision,
        decision === 'rejected' ? rejectReason.trim() : undefined,
      )
      setMessage(`Equipment request ${updated.status}.`)
      onDecided(updated)
    } catch (caught) {
      setError(caught instanceof Error
        ? caught.message
        : 'Could not update the equipment request status.')
    } finally {
      setUpdatingStatus(false)
    }
  }

  return (
    <Card className="tech-card">
      <h2>Equipment request status</h2>
      {request.status !== 'processing' ? (
        <p>
          Status: <strong>{request.status}</strong>
        </p>
      ) : (
        <>
          <p>Current status: <strong>{request.status}</strong></p>
          <p>Reserved against requested quantity for the full event period:</p>
          {lines.length === 0 ? (
            <p>No itemised equipment quantities were requested.</p>
          ) : (
            <ul>
              {lines.map((line) => (
                <li key={line.equipmentId}>
                  {line.equipmentName}:{' '}
                  {reservedForEventPeriod(reservations, line.equipmentId, request.eventStart, request.eventEnd)} of{' '}
                  {line.quantity} reserved
                </li>
              ))}
            </ul>
          )}
          <div className="field">
            <label htmlFor="equipment-request-decision">Decision</label>
            <select
              id="equipment-request-decision"
              value={decision}
              onChange={(event) => setDecision(event.target.value as 'approved' | 'rejected')}
              disabled={updatingStatus}
            >
              <option value="approved">Approve</option>
              <option value="rejected">Reject</option>
            </select>
          </div>
          {decision === 'rejected' && (
            <div className="field">
              <label htmlFor="equipment-request-reject-reason">Reason for rejection</label>
              <textarea
                id="equipment-request-reject-reason"
                value={rejectReason}
                maxLength={2000}
                onChange={(event) => setRejectReason(event.target.value)}
                disabled={updatingStatus}
              />
            </div>
          )}
          {decision === 'approved' && !allItemsReserved && (
            <p role="alert" className="error-text">
              Reserve every requested quantity for the full event period before approving.
            </p>
          )}
          <div className="tech-actions">
            <Button
              type="button"
              onClick={handleUpdateStatus}
              disabled={!ready || updatingStatus
                || (decision === 'approved' && !allItemsReserved)
                || (decision === 'rejected' && rejectReason.trim() === '')}
            >
              {updatingStatus ? 'Saving…' : `Save ${decision} decision`}
            </Button>
          </div>
        </>
      )}
      {message && <Notice>{message}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
    </Card>
  )
}
