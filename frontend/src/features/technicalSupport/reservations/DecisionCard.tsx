import { useEffect, useRef, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { countOf } from '../../../lib/greeting'
import type {
  EquipmentRequestDecision,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
} from './reservation.types'
import { updateEquipmentRequestStatus } from './reservationApi'
import { everyLineReserved, reservedForEventPeriod } from './reservationUtils'
import '../technicalSupport.css'
import './fulfilment.css'

/** TS06: approve once every requested item is reserved for the whole event
 * (spares count), or reject with a reason. A decided request shows its
 * decision, read-only. `ready` is false while the figures are (re)loading. */
export function DecisionCard({ request, lines, reservations, ready, rejectReason, onDecided }: {
  request: EquipmentRequestSummary
  lines: RequestLine[]
  reservations: EquipmentReservation[]
  ready: boolean
  /** The reason, when this page rejected it (the request list does not carry it). */
  rejectReason: string | null
  onDecided: (decision: EquipmentRequestDecision) => void
}) {
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const reasonRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (rejecting) reasonRef.current?.focus()
  }, [rejecting])

  const covered = everyLineReserved(lines, reservations, request.eventStart, request.eventEnd)
  const left = lines.filter((line) =>
    reservedForEventPeriod(reservations, line.equipmentId, request.eventStart, request.eventEnd) < line.quantity).length

  async function decide(status: 'approved' | 'rejected') {
    setSaving(true)
    setError(null)
    try {
      onDecided(await updateEquipmentRequestStatus(request.requestId, status,
        status === 'rejected' ? reason.trim() : undefined))
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not save the decision.')
    } finally {
      setSaving(false)
    }
  }

  if (request.status !== 'processing') {
    // EC-NEW3: a flagged request (D37) is still approved with its reservations held;
    // a superseded one (D36) was replaced by the coordinator's revision.
    const approved = request.status === 'approved' || request.status === 'attention'
    return (
      <Card className="tech-card decision">
        <h2>Decision</h2>
        <Notice tone={approved ? 'success' : request.status === 'superseded' ? 'info' : 'danger'} as="div">
          {approved ? '✓ This request was approved.'
            : request.status === 'superseded' ? 'This request was replaced by a revision.' : 'This request was rejected.'}
          {request.status === 'attention' ? <p>The coordinator is reviewing a change to the event.</p> : null}
          {request.status === 'rejected' && rejectReason ? <p className="decision__reason">Reason: {rejectReason}</p> : null}
        </Notice>
      </Card>
    )
  }

  return (
    <Card className="tech-card decision">
      <div className="decision__row">
        <div>
          <h2>Decision</h2>
          <p className={covered ? 'decision__state decision__state--ok' : 'decision__state'}>
            {!ready ? 'Checking reservations…'
              : covered ? '✓ Everything requested is reserved'
                : `${countOf(left, 'item')} still ${left === 1 ? 'needs' : 'need'} reserving`}
          </p>
        </div>
        {!rejecting ? (
          <div className="tech-actions">
            <Button variant="success" type="button" disabled={!ready || !covered || saving} onClick={() => decide('approved')}
              aria-describedby={covered ? undefined : 'approve-hint'}>
              {saving ? 'Saving…' : 'Approve request'}
            </Button>
            <Button variant="secondary" type="button" disabled={saving}
              onClick={() => setRejecting(true)}>
              Reject…
            </Button>
          </div>
        ) : null}
      </div>
      {!covered && !rejecting ? (
        <p id="approve-hint" className="field-hint">Approve is available once every requested item is reserved for the whole event.</p>
      ) : null}
      {rejecting ? (
        <form className="decision__reject" onSubmit={(e) => { e.preventDefault(); if (reason.trim()) void decide('rejected') }}>
          <div className="field">
            <label htmlFor="equipment-request-reject-reason">Reason for rejection</label>
            <textarea id="equipment-request-reject-reason" ref={reasonRef} value={reason} maxLength={2000} rows={3}
              required onChange={(e) => setReason(e.target.value)} disabled={saving} />
          </div>
          <div className="tech-actions">
            <Button type="submit" disabled={saving || reason.trim() === ''}>
              {saving ? 'Saving…' : 'Reject request'}
            </Button>
            <Button variant="ghost" type="button" disabled={saving}
              onClick={() => { setRejecting(false); setError(null) }}>Cancel</Button>
          </div>
        </form>
      ) : null}
      {error ? <Notice tone="danger">{error}</Notice> : null}
    </Card>
  )
}
