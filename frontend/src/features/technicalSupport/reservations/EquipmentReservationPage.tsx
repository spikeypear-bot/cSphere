import { useEffect, useState } from 'react'
import type {
  EquipmentAvailability,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
  TimePeriod,
} from './reservation.types'
import {
  fetchAvailability,
  fetchProcessingRequests,
  fetchRequestLines,
  fetchReservationsForEvent,
  reserveEquipment,
} from './reservationApi'
import { 
  describeReservation, 
  describeReserveError, 
  formatMoment,
  toLocalInputValue 
} from './reservationUtils'


export function EquipmentReservationPage() {
  // ---- AC 1: events needing equipment ----
  const [requests, setRequests] = useState<EquipmentRequestSummary[]>([])
  const [loadingRequests, setLoadingRequests] = useState(false)
  const [selectedRequest, setSelectedRequest] = useState<EquipmentRequestSummary | null>(null)

  // ---- AC 2: that event's requirements ----
  const [lines, setLines] = useState<RequestLine[]>([])

  // ---- The period Technical Support is booking for ----
  const [period, setPeriod] = useState<TimePeriod>({
    start: '2026-09-25T09:00',
    end: '2026-09-25T17:00',
  })

  // ---- AC 3 + 4: availability for that period ----
  const [availability, setAvailability] = useState<EquipmentAvailability[]>([])
  const [loadingAvailability, setLoadingAvailability] = useState(false)

  // ---- AC 5: the reserve form ----
  const [formEquipmentId, setFormEquipmentId] = useState('')
  const [formQuantity, setFormQuantity] = useState(1)
  const [formSerial, setFormSerial] = useState('') // optional
  const [saving, setSaving] = useState(false)

  // ---- AC 10 + 11: confirmation and the event's saved reservations ----
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reservations, setReservations] = useState<EquipmentReservation[]>([])
  const [reloadCount, setReloadCount] = useState(0)

  const periodIsValid =
    period.start !== '' && period.end !== '' && period.end > period.start
  const chosenEquipment = availability.find((a) => a.equipmentId === formEquipmentId) ?? null
  const formIsValid =
    formEquipmentId !== '' &&
    formQuantity >= 1 &&
    (chosenEquipment ? formQuantity <= chosenEquipment.availableQuantity : true)

  // ---- Load AC 1 once ----
  useEffect(() => {
    setLoadingRequests(true)
    fetchProcessingRequests()
      .then(setRequests)
      .catch(() => setError('Could not load events needing equipment.'))
      .finally(() => setLoadingRequests(false))
  }, [])

  // ---- AC 2 + 11: load when an event is selected ----
  useEffect(() => {
    if (!selectedRequest) {
      setLines([])
      setReservations([])
      return
    }
    let cancelled = false
    fetchRequestLines(selectedRequest.requestId)
      .then((data) => { if (!cancelled) setLines(data) })
      .catch(() => { if (!cancelled) setError('Could not load equipment requirements.') })
    fetchReservationsForEvent(selectedRequest.eventId)
      .then((data) => { if (!cancelled) setReservations(data) })
      .catch(() => { if (!cancelled) setError('Could not load existing reservations.') })
    return () => { cancelled = true }
  }, [selectedRequest, reloadCount])

  // ---- AC 3 + 4: load availability whenever the event or period changes ----
  useEffect(() => {
    if (!selectedRequest || !periodIsValid) {
      setAvailability([])
      return
    }
    let cancelled = false
    setLoadingAvailability(true)
    fetchAvailability(selectedRequest.requestId, period)
      .then((data) => { if (!cancelled) setAvailability(data) })
      .catch(() => { if (!cancelled) setError('Could not load availability.') })
      .finally(() => { if (!cancelled) setLoadingAvailability(false) })
    return () => { cancelled = true }
  }, [selectedRequest, period, periodIsValid, reloadCount])

  function handleSelectRequest(req: EquipmentRequestSummary) {
  setSelectedRequest(req)
  setPeriod({
    start: toLocalInputValue(req.eventStart),
    end: toLocalInputValue(req.eventEnd),
  })
  setFormEquipmentId('')
  setFormQuantity(1)
  setFormSerial('')
  setMessage(null)
  setError(null)
}


  async function handleReserve() {
    if (!selectedRequest || !chosenEquipment) return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      const reservation = await reserveEquipment(
        selectedRequest.eventId,
        chosenEquipment.equipmentId,
        formQuantity,
        chosenEquipment.serialised && formSerial !== '' ? formSerial : null,
        period,
      )
      setMessage(
        `Reserved ${reservation.quantity} × ${reservation.equipmentName} for this event.`,
      )
      setFormEquipmentId('')
      setFormQuantity(1)
      setFormSerial('')
      setReloadCount((n) => n + 1) // refreshes availability + the reservation list
    } catch (e) {
      setError(describeReserveError(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section>
      <h1>Equipment Reservations</h1>

      <h2>Events needing equipment</h2>
      {loadingRequests && <p>Loading events…</p>}
      <ul>
      {requests.map((req) => (
        <li key={req.requestId}>
          <button
            type="button"
            onClick={() => handleSelectRequest(req)}
            aria-pressed={selectedRequest?.requestId === req.requestId}
          >
            {req.eventName} ({formatMoment(req.eventStart)} – {formatMoment(req.eventEnd)}) —{' '}
            {req.technicalRequirement}
          </button>
        </li>
      ))}
    </ul>

      {selectedRequest && (
        <>
          <h2>{selectedRequest.eventName}</h2>
          <p>{selectedRequest.technicalRequirement}</p>

          <h2>Requirements</h2>
          <ul>
            {lines.map((line) => (
              <li key={line.equipmentId}>
                {line.equipmentName}: {line.quantity} needed
              </li>
            ))}
          </ul>

          <fieldset>
            <legend>Period to reserve for</legend>
            <label htmlFor="period-start">Start</label>
            <input
              id="period-start"
              type="datetime-local"
              value={period.start}
              onChange={(e) => setPeriod({ ...period, start: e.target.value })}
            />
            <label htmlFor="period-end">End</label>
            <input
              id="period-end"
              type="datetime-local"
              value={period.end}
              onChange={(e) => setPeriod({ ...period, end: e.target.value })}
            />
          </fieldset>
          {!periodIsValid && <p role="alert">Please choose an end time after the start time.</p>}

          <h2>Availability for this period</h2>
          {loadingAvailability && <p>Loading availability…</p>}
          <ul>
            {availability.map((a) => (
              <li key={a.equipmentId}>
                {a.equipmentName}: {a.availableQuantity} of {a.totalQuantity} available
                {a.serialised ? ' (serialised)' : ''}
              </li>
            ))}
          </ul>

          <h2>Reserve equipment</h2>
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

          <label htmlFor="reserve-quantity">Quantity</label>
          <input
            id="reserve-quantity"
            type="number"
            min={1}
            value={formQuantity}
            onChange={(e) => setFormQuantity(Number(e.target.value))}
            disabled={saving || (chosenEquipment?.serialised ?? false)}
          />

          {chosenEquipment?.serialised && (
            <>
              <label htmlFor="reserve-serial">Serial number (optional — leave blank for any unit)</label>
              <input
                id="reserve-serial"
                type="text"
                value={formSerial}
                onChange={(e) => setFormSerial(e.target.value)}
                disabled={saving}
              />
            </>
          )}

          {chosenEquipment && formQuantity > chosenEquipment.availableQuantity && (
            <p role="alert">
              Only {chosenEquipment.availableQuantity} available for this period.
            </p>
          )}

          <button type="button" onClick={handleReserve} disabled={!formIsValid || saving}>
            {saving ? 'Reserving…' : 'Reserve'}
          </button>

          <h2>Reservations for this event</h2>
          {reservations.length === 0 ? (
            <p>None yet.</p>
          ) : (
            <ul>
              {reservations.map((r) => (
                <li key={r.logId}>
                  {r.quantity} × {r.equipmentName}
                  {r.serialNumber ? ` (serial ${r.serialNumber})` : ''} —{' '}
                  {describeReservation(r.loanedFrom, r.loanedUntil)}
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}