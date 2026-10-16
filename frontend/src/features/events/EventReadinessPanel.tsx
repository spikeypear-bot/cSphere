import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventReadinessDto } from '../../types/event'
import './EventLifecycle.css'

type Item = EventReadinessDto['items'][number]

const STATE_TEXT: Record<Item['state'], string> = {
  complete: 'Done',
  outstanding: 'To do',
  waiting: 'Waiting',
  not_needed: 'Not needed',
}

/**
 * EC08: what is still needed before this event can go for its Operational
 * Safety Check, and the one next action. The server works out readiness, so
 * the buttons shown here are exactly the ones it will accept.
 */
export function EventReadinessPanel({ eventId, onChanged }: { eventId: string; onChanged: () => void }) {
  const [readiness, setReadiness] = useState<EventReadinessDto | null>(null)
  const [failed, setFailed] = useState(false)
  const [reload, setReload] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errorReasons, setErrorReasons] = useState<string[]>([])
  const [withdrawing, setWithdrawing] = useState(false)
  const [reason, setReason] = useState('')
  const [done, setDone] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    apiClient.get<EventReadinessDto>(`/events/${eventId}/lifecycle`)
      .then((value) => {
        if (cancelled) return
        if (value && Array.isArray(value.items)) setReadiness(value)
        else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [eventId, reload])

  if (failed || !readiness) return null

  async function act(path: string, body: unknown, message: string) {
    setBusy(true)
    setError(null)
    setErrorReasons([])
    setDone(null)
    try {
      await apiClient.post(`/events/${eventId}/${path}`, body)
      setWithdrawing(false)
      setReason('')
      setDone(message)
      setReload((n) => n + 1)
      onChanged()
    } catch (err) {
      /* v8 ignore else -- @preserve: see the stated exclusion below */
      if (err instanceof ApiClientError) {
        setError(err.message)
        // What no longer holds (e.g. a booking back to pending), so the
        // coordinator is told what changed, not just that it failed.
        setErrorReasons(err.missingFields ?? [])
        // The server refused because something changed since this page
        // loaded: show the checklist and status as they are now.
        if (err.status === 409 || err.status === 422) {
          setReload((n) => n + 1)
          onChanged()
        }
      } else {
        // Stated coverage exclusion (docs/testing-strategy.md): apiClient turns
        // every failure, network included, into an ApiClientError, so this
        // only guards against a programming error and no test can reach it.
        /* v8 ignore next -- @preserve */
        setError('That did not go through. Please try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  const code = readiness.journeyStatus.code
  const remaining = readiness.items.filter((i) => i.state === 'outstanding' || i.state === 'waiting').length
  const finished = code === 'CONFIRMED' || code === 'COMPLETED' || code === 'CANCELLED'

  return (
    <Card className="readiness" role="region" aria-labelledby="readiness-heading">
      <div className="readiness__head">
        <h2 id="readiness-heading">{finished ? 'Arrangements' : 'Ready for confirmation?'}</h2>
        {!finished ? (
          <p className="field-hint">
            {remaining === 0 ? 'Everything needed is in place.' : `${remaining} of ${readiness.items.length} still to sort out.`}
          </p>
        ) : null}
      </div>

      {readiness.safetyFeedback && code === 'SAFETY_CHANGES_REQUESTED' ? (
        <div className="readiness__feedback" role="note">
          <strong>Safety Officer's feedback</strong>
          <p>{readiness.safetyFeedback}</p>
          <p className="field-hint">Make the changes, then send the event for review again.</p>
        </div>
      ) : null}

      <ul className="readiness__list">
        {readiness.items.map((item) => (
          <li key={item.key} className="readiness__item" data-state={item.state}>
            <span className="readiness__icon" aria-hidden="true" />
            <div className="readiness__body">
              <span className="readiness__label">{item.label}</span>
              <span className="readiness__detail">{item.detail}</span>
            </div>
            <span className="readiness__state">{STATE_TEXT[item.state]}</span>
            {item.actionPath && item.state === 'outstanding' ? (
              <Link className="readiness__action" to={item.actionPath}>Resolve</Link>
            ) : null}
          </li>
        ))}
      </ul>

      {readiness.blockers.length > 0 && !finished && code !== 'AWAITING_SAFETY_REVIEW' ? (
        <div className="readiness__blockers" role="status">
          <strong>Not ready yet</strong>
          <ul>
            {readiness.blockers.map((b) => <li key={b}>{b}</li>)}
          </ul>
        </div>
      ) : null}

      {done ? <p className="readiness__done" role="status">{done}</p> : null}
      {error ? (
        <div className="readiness__error" role="alert">
          <p>{error}</p>
          {errorReasons.length > 0 ? <ul>{errorReasons.map((r) => <li key={r}>{r}</li>)}</ul> : null}
        </div>
      ) : null}

      <div className="readiness__actions">
        {readiness.canSendForReview ? (
          <Button disabled={busy} onClick={() => void act('safety-review', undefined,
            'Sent for the Operational Safety Check. You will be able to confirm once it is approved.')}>
            {busy ? 'Sending…' : 'Send for safety review'}
          </Button>
        ) : null}
        {readiness.canConfirm ? (
          <Button disabled={busy} onClick={() => void act('confirm', undefined,
            'Event confirmed. The organiser and the staff involved have been notified.')}>
            {busy ? 'Confirming…' : 'Confirm event'}
          </Button>
        ) : null}
        {readiness.canWithdraw && !withdrawing ? (
          <Button variant="secondary" disabled={busy} onClick={() => setWithdrawing(true)}>
            Withdraw from review
          </Button>
        ) : null}
      </div>

      {withdrawing ? (
        <form className="readiness__withdraw" onSubmit={(e) => {
          e.preventDefault()
          void act('safety-review/withdraw', { reason: reason.trim() }, 'Withdrawn from safety review. The event is back in Planning.')
        }}>
          <label htmlFor="withdraw-reason">Why are you withdrawing it?</label>
          <textarea id="withdraw-reason" rows={3} maxLength={2000} required value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="For example: the organiser changed the expected attendance." />
          <span className="field-hint">The reason is kept in the event's history.</span>
          <div className="readiness__actions">
            <Button variant="secondary" type="button" onClick={() => { setWithdrawing(false); setReason('') }}>
              Keep in review
            </Button>
            <Button type="submit" disabled={busy || reason.trim() === ''}>
              {busy ? 'Withdrawing…' : 'Withdraw'}
            </Button>
          </div>
        </form>
      ) : null}
    </Card>
  )
}
