import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { ApiClientError } from '../../lib/apiClient'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import { EquipmentImageCredits } from '../equipment/EquipmentImageCredits'
import { EquipmentPhoto } from '../technicalSupport/damage/EquipmentPhoto'
import { fetchEventDamage, resolveShortfall } from '../technicalSupport/damage/equipmentDamageApi'
import { DESCRIPTION_MAX, outcomeLabel, type EventEquipmentDamage } from '../technicalSupport/damage/equipmentDamage.types'
import '../technicalSupport/damage/equipmentDamage.css'

/**
 * TS09 AC16–AC20: damaged equipment this event relied on, with what Technical
 * Support decided. The assigned coordinator can request the shortfall or
 * resolve it with a note; the Lead reads the same list without actions.
 * Hidden while nothing was reported, so the event page stays uncluttered.
 */
export function EquipmentDamagePanel({ eventId, readOnly }: { eventId: string; readOnly: boolean }) {
  const [items, setItems] = useState<EventEquipmentDamage[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    fetchEventDamage(eventId)
      .then((loaded) => {
        if (cancelled) return
        // A malformed answer is a failure too, never a crash of the event page.
        if (Array.isArray(loaded)) {
          setItems(loaded)
          setFailed(false)
        } else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => { cancelled = true }
  }, [eventId, attempt])

  if (failed) {
    return (
      <Card className="event-details__venue">
        <h2>Damaged equipment</h2>
        {/* A status, not an alert: this side panel failing must not drown out the
            event page's own alerts; the rest of the page still works. */}
        <div role="status" className="equipment-damage__error">
          <p>Damaged equipment for this event could not be loaded.</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      </Card>
    )
  }
  if (!items || items.length === 0) return null

  const replace = (updated: EventEquipmentDamage) =>
    setItems((current) => (current ?? []).map((item) => (item.outcomeId === updated.outcomeId ? updated : item)))

  return (
    <Card className="event-details__venue" role="region" aria-labelledby="equipment-damage-heading">
      <h2 id="equipment-damage-heading">Damaged equipment</h2>
      {readOnly ? <p className="field-hint">Read-only: the assigned coordinator handles any shortfall.</p> : null}
      <ul className="equipment-damage__events" aria-label="Damaged equipment">
        {items.map((item) => (
          <li key={item.outcomeId}>
            <DamageItem eventId={eventId} item={item} readOnly={readOnly} onResolved={replace} />
          </li>
        ))}
      </ul>
      <EquipmentImageCredits names={[...new Set(items.map((item) => item.equipmentName))]} />
    </Card>
  )
}

function DamageItem({ eventId, item, readOnly, onResolved }: {
  eventId: string
  item: EventEquipmentDamage
  readOnly: boolean
  onResolved: (updated: EventEquipmentDamage) => void
}) {
  const [resolving, setResolving] = useState(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const trimmed = note.trim()
  const noteProblem = !trimmed ? 'Explain why the shortfall is acceptable.'
    : trimmed.length > DESCRIPTION_MAX ? `Keep the note to ${DESCRIPTION_MAX} characters.` : null
  const [triedResolve, setTriedResolve] = useState(false)

  async function resolve() {
    setTriedResolve(true)
    if (noteProblem) return
    setBusy(true)
    setError(null)
    try {
      onResolved(await resolveShortfall(eventId, item.outcomeId, trimmed))
      setResolving(false)
      setNote('')
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'The shortfall could not be resolved. Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="equipment-damage__event">
      <div className="equipment-damage__chosen">
        <EquipmentPhoto name={item.equipmentName} />
        <div>
          <div className="equipment-damage__event-heading">
            <h3>{item.equipmentName}{item.serialNumber ? ` · ${item.serialNumber}` : ` · ${item.damagedQuantity} damaged`}</h3>
            <span className="chip">{outcomeLabel(item.outcome, item.shortfall, item.replacementSerialNumber)}</span>
          </div>
          <p>{item.description}</p>
          <p className="field-hint">
            Reported {formatEventDateTime(item.reportedAt)} · from {formatEventDateTime(item.periodStart)}
            {item.periodEnd ? ` until ${formatEventDateTime(item.periodEnd)}` : ' until further notice'}
          </p>
        </div>
      </div>
      {item.resolution === 'NOTE' ? <p>Resolved with a note: {item.resolutionNote}</p> : null}
      {item.resolution === 'ADDITIONAL_REQUEST' ? <p>Resolved: the additional equipment request was approved.</p> : null}
      {!item.resolution && item.additionalRequestId ? (
        <p>Additional request submitted ({item.additionalRequestStatus ?? 'processing'}).</p>
      ) : null}
      {!readOnly && (item.canRequestAdditional || item.canResolve) ? (
        <div className="equipment-damage__actions">
          {item.canRequestAdditional ? (
            <Link className="button button--primary"
              to={`/coordinator/events/${eventId}/equipment-request?shortfall=${encodeURIComponent(item.outcomeId)}`}>
              Request {item.shortfall} more
            </Link>
          ) : null}
          {item.canResolve && !resolving ? (
            <Button variant="secondary" onClick={() => setResolving(true)}>Resolve with a note</Button>
          ) : null}
        </div>
      ) : null}
      {resolving ? (
        <div className="equipment-damage__replace">
          <label className="equipment-damage__field">
            Why the event can go ahead without the {item.shortfall} missing
            <textarea rows={3} value={note} maxLength={DESCRIPTION_MAX} onChange={(e) => setNote(e.target.value)}
              aria-invalid={Boolean(triedResolve && noteProblem)} />
          </label>
          {triedResolve && noteProblem ? <p className="field-error">{noteProblem}</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          <div className="equipment-damage__actions">
            <Button variant="secondary" disabled={busy} onClick={() => { setResolving(false); setNote(''); setTriedResolve(false) }}>Cancel</Button>
            <Button disabled={busy} onClick={() => void resolve()}>{busy ? 'Saving…' : 'Resolve shortfall'}</Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
