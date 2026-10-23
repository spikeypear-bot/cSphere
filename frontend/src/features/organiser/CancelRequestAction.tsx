import { useState } from 'react'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventRequestDto } from '../../types/eventRequest'
import { reasonProblem } from '../../types/eventCancellation'

/**
 * EO07 AC2: before approval (Submitted, Under Review, Clarification Required)
 * the organiser cancels their request at once, with a reason. Nothing is sent
 * until they confirm; "Keep request" closes the form without a call.
 */
export function CancelRequestAction({ requestId, eventName, onCancelled }: {
  requestId: string
  eventName: string
  onCancelled: (updated: EventRequestDto) => void
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fieldId = `cancel-request-reason-${requestId}`

  async function confirm() {
    const problem = reasonProblem(reason, 'Please give a reason for cancelling this request.')
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const updated = await apiClient.post<EventRequestDto>(`/event-requests/${requestId}/cancel`,
        { reason: reason.trim() })
      setOpen(false)
      setReason('')
      onCancelled(updated)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'The request could not be cancelled. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <Button variant="secondary" onClick={() => setOpen(true)} aria-label={`Cancel request ${eventName}`}>
        Cancel request
      </Button>
    )
  }
  return (
    <div className="organiser-home__cancel">
      <p>Cancelling stops the review straight away. This cannot be undone.</p>
      <label htmlFor={fieldId}>Reason for cancelling</label>
      <textarea id={fieldId} rows={2} value={reason}
        onChange={(e) => setReason(e.target.value)} />
      {error ? <p role="alert">{error}</p> : null}
      <div className="organiser-home__cancel-actions">
        <Button variant="secondary" disabled={busy}
          onClick={() => { setOpen(false); setReason(''); setError(null) }}>
          Keep request
        </Button>
        <Button disabled={busy} onClick={() => void confirm()}>
          {busy ? 'Cancelling…' : 'Confirm cancellation'}
        </Button>
      </div>
    </div>
  )
}
