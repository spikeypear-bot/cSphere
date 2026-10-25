import { useEffect, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import '../technicalSupport.css'
import type {
  EquipmentAvailability,
  EquipmentReservation,
  EquipmentRequestSummary,
  TimePeriod,
} from './reservation.types'
import { fetchAvailability, reserveEquipment } from './reservationApi'
import { describeReserveError, toLocalInputValue } from './reservationUtils'

/** TS02 AC3–AC10: availability for a chosen period and the reserve form.
 * The period starts as the event's; `onReserved` lets the page reload what
 * depends on the event's reservations. The form only shows while the
 * request is awaiting review. */
export function ReserveSection({ request, reloadKey = 0, onReserved }: {
  request: EquipmentRequestSummary
  reloadKey?: number
  onReserved: (reservation: EquipmentReservation) => void
}) {
  const [period, setPeriod] = useState<TimePeriod>(() => ({
    start: toLocalInputValue(request.eventStart),
    end: toLocalInputValue(request.eventEnd),
  }))
  const [ownReloads, setOwnReloads] = useState(0)
  const [result, setResult] = useState<{ key: string; availability: EquipmentAvailability[] } | null>(null)
  const [loadFailedKey, setLoadFailedKey] = useState<string | null>(null)

  const [formEquipmentId, setFormEquipmentId] = useState('')
  const [formQuantity, setFormQuantity] = useState(1)
  const [formSerial, setFormSerial] = useState('') // optional
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const periodIsValid = period.start !== '' && period.end !== '' && period.end > period.start
  const key = JSON.stringify([request.requestId, period.start, period.end, reloadKey, ownReloads])
  const availability = periodIsValid && result?.key === key ? result.availability : []
  const loadingAvailability = periodIsValid && result?.key !== key && loadFailedKey !== key

  // AC 3 + 4: availability whenever the event, the period or a reservation changes.
  useEffect(() => {
    if (!periodIsValid) return
    let cancelled = false
    fetchAvailability(request.requestId, period)
      .then((data) => { if (!cancelled) setResult({ key, availability: data }) })
      .catch(() => { if (!cancelled) setLoadFailedKey(key) })
    return () => { cancelled = true }
  }, [key, request.requestId, period, periodIsValid])

  const chosenEquipment = availability.find((a) => a.equipmentId === formEquipmentId) ?? null
  const formIsValid =
    formEquipmentId !== '' &&
    formQuantity >= 1 &&
    (chosenEquipment ? formQuantity <= chosenEquipment.availableQuantity : true)

  async function handleReserve() {
    if (!chosenEquipment || request.status !== 'processing') return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      const reservation = await reserveEquipment(
        request.eventId,
        chosenEquipment.equipmentId,
        formQuantity,
        chosenEquipment.serialised && formSerial !== '' ? formSerial : null,
        period,
      )
      setMessage(`Reserved ${reservation.quantity} × ${reservation.equipmentName} for this event.`)
      setFormEquipmentId('')
      setFormQuantity(1)
      setFormSerial('')
      setOwnReloads((n) => n + 1) // refreshes availability
      onReserved(reservation)
    } catch (e) {
      setError(describeReserveError(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="tech-card">
      <h2>Reserve equipment</h2>
      <fieldset className="tech-period">
        <legend>Period to reserve for</legend>
        <div className="field">
          <label htmlFor="period-start">Start</label>
          <input
            id="period-start"
            type="datetime-local"
            value={period.start}
            onChange={(e) => setPeriod({ ...period, start: e.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="period-end">End</label>
          <input
            id="period-end"
            type="datetime-local"
            value={period.end}
            onChange={(e) => setPeriod({ ...period, end: e.target.value })}
          />
        </div>
      </fieldset>
      {!periodIsValid && (
        <p role="alert" className="error-text">Please choose an end time after the start time.</p>
      )}

      <h3>Availability for this period</h3>
      {loadingAvailability && <p>Loading availability…</p>}
      {loadFailedKey === key && <p role="alert" className="error-text">Could not load availability.</p>}
      <ul>
        {availability.map((a) => (
          <li key={a.equipmentId}>
            {a.equipmentName}: {a.availableQuantity} of {a.totalQuantity} available
            {a.serialised ? ' (serialised)' : ''}
          </li>
        ))}
      </ul>

      {request.status === 'processing' ? (
        <>
          <div className="tech-form">
            <div className="field">
              <label htmlFor="reserve-equipment">Equipment</label>
              <select
                id="reserve-equipment"
                value={formEquipmentId}
                onChange={(e) => { setFormEquipmentId(e.target.value); setFormSerial('') }}
                disabled={saving}
              >
                <option value="">-- choose --</option>
                {availability.map((a) => (
                  <option key={a.equipmentId} value={a.equipmentId}>
                    {a.equipmentName}
                  </option>
                ))}
              </select>
            </div>

            <div className="field">
              <label htmlFor="reserve-quantity">Quantity</label>
              <input
                id="reserve-quantity"
                type="number"
                min={1}
                value={formQuantity}
                onChange={(e) => setFormQuantity(Number(e.target.value))}
                disabled={saving || (chosenEquipment?.serialised ?? false)}
              />
            </div>

            {chosenEquipment?.serialised && (
              <div className="field">
                <label htmlFor="reserve-serial">Serial number (optional — leave blank for any unit)</label>
                <input
                  id="reserve-serial"
                  type="text"
                  value={formSerial}
                  onChange={(e) => setFormSerial(e.target.value)}
                  disabled={saving}
                />
              </div>
            )}
          </div>

          {chosenEquipment && formQuantity > chosenEquipment.availableQuantity && (
            <p role="alert" className="error-text">
              Only {chosenEquipment.availableQuantity} available for this period.
            </p>
          )}

          <div className="tech-actions">
            <Button type="button" onClick={handleReserve} disabled={!formIsValid || saving}>
              {saving ? 'Reserving…' : 'Reserve'}
            </Button>
          </div>
        </>
      ) : (
        <p className="field-hint">This request has been decided, so nothing more can be reserved against it.</p>
      )}

      {message && <Notice>{message}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
    </Card>
  )
}
