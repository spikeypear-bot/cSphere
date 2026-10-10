import { useEffect, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import '../technicalSupport.css'
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
  updateEquipmentRequestStatus,
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
  const [loadingRequests, setLoadingRequests] = useState(true)
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
  const [detailsLoadedForRequest, setDetailsLoadedForRequest] = useState<string | null>(null)
  const [decision, setDecision] = useState<'approved' | 'rejected'>('approved')
  const [rejectReason, setRejectReason] = useState('')
  const [updatingStatus, setUpdatingStatus] = useState(false)

  const periodIsValid =
    period.start !== '' && period.end !== '' && period.end > period.start
  const chosenEquipment = availability.find((a) => a.equipmentId === formEquipmentId) ?? null
  const formIsValid =
    formEquipmentId !== '' &&
    formQuantity >= 1 &&
    (chosenEquipment ? formQuantity <= chosenEquipment.availableQuantity : true)
  const eventStart = selectedRequest ? new Date(selectedRequest.eventStart).getTime() : 0
  const eventEnd = selectedRequest ? new Date(selectedRequest.eventEnd).getTime() : 0
  const reservedQuantity = (equipmentId: string) => reservations
    .filter((reservation) => reservation.equipmentId === equipmentId
      && new Date(reservation.loanedFrom).getTime() <= eventStart
      && new Date(reservation.loanedUntil).getTime() >= eventEnd)
    .reduce((total, reservation) => total + reservation.quantity, 0)
  const allItemsReserved = lines.every((line) => reservedQuantity(line.equipmentId) >= line.quantity)
  const requestDetailsLoaded = selectedRequest !== null
    && detailsLoadedForRequest === selectedRequest.requestId

  // ---- Load AC 1 once ----
  useEffect(() => {
    fetchProcessingRequests()
      .then(setRequests)
      .catch(() => setError('Could not load events needing equipment.'))
      .finally(() => setLoadingRequests(false))
  }, [])

  // ---- AC 2 + 11: load when an event is selected ----
  useEffect(() => {
    if (!selectedRequest) return
    let cancelled = false
    Promise.all([
      fetchRequestLines(selectedRequest.requestId),
      fetchReservationsForEvent(selectedRequest.eventId),
    ])
      .then(([loadedLines, loadedReservations]) => {
        if (cancelled) return
        setLines(loadedLines)
        setReservations(loadedReservations)
        setDetailsLoadedForRequest(selectedRequest.requestId)
      })
      .catch(() => {
        if (!cancelled) setError('Could not load equipment requirements or existing reservations.')
      })
    return () => { cancelled = true }
  }, [selectedRequest, reloadCount])

  // ---- AC 3 + 4: load availability whenever the event or period changes ----
  useEffect(() => {
    if (!selectedRequest || !periodIsValid) return
    let cancelled = false
    fetchAvailability(selectedRequest.requestId, period)
      .then((data) => { if (!cancelled) setAvailability(data) })
      .catch(() => { if (!cancelled) setError('Could not load availability.') })
      .finally(() => { if (!cancelled) setLoadingAvailability(false) })
    return () => { cancelled = true }
  }, [selectedRequest, period, periodIsValid, reloadCount])

  function handleSelectRequest(req: EquipmentRequestSummary) {
  if (selectedRequest?.requestId === req.requestId) return
  setSelectedRequest(req)
  setDetailsLoadedForRequest(null)
  setLines([])
  setReservations([])
  setAvailability([])
  setLoadingAvailability(true)
  setPeriod({
    start: toLocalInputValue(req.eventStart),
    end: toLocalInputValue(req.eventEnd),
  })
  setFormEquipmentId('')
  setFormQuantity(1)
  setFormSerial('')
  setDecision('approved')
  setRejectReason('')
  setMessage(null)
  setError(null)
}

  function handlePeriodChange(nextPeriod: TimePeriod) {
    setPeriod(nextPeriod)
    setAvailability([])
    setLoadingAvailability(
      selectedRequest !== null
        && nextPeriod.start !== ''
        && nextPeriod.end !== ''
        && nextPeriod.end > nextPeriod.start,
    )
  }

  async function handleReserve() {
    if (!selectedRequest || !chosenEquipment || selectedRequest.status !== 'processing') return
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
      setDetailsLoadedForRequest(null)
      setAvailability([])
      setLoadingAvailability(true)
      setReloadCount((n) => n + 1) // refreshes availability + the reservation list
    } catch (e) {
      setError(describeReserveError(e))
    } finally {
      setSaving(false)
    }
  }

  async function handleUpdateStatus() {
    if (!selectedRequest || !requestDetailsLoaded) return
    if (decision === 'approved' && !allItemsReserved) return
    if (decision === 'rejected' && rejectReason.trim() === '') return

    setUpdatingStatus(true)
    setMessage(null)
    setError(null)
    try {
      const updated = await updateEquipmentRequestStatus(
        selectedRequest.requestId,
        decision,
        decision === 'rejected' ? rejectReason.trim() : undefined,
      )
      setSelectedRequest({ ...selectedRequest, status: updated.status })
      setRequests((current) => current.filter((request) => request.requestId !== updated.requestId))
      setMessage(`Equipment request ${updated.status}.`)
      setLoadingAvailability(true)
      setReloadCount((count) => count + 1)
    } catch (caught) {
      setError(caught instanceof Error
        ? caught.message
        : 'Could not update the equipment request status.')
    } finally {
      setUpdatingStatus(false)
    }
  }

  return (
    <section className="page">
      <PageHeader title="Equipment Reservations" />

      <Card className="tech-card">
        <h2>Events needing equipment</h2>
        {loadingRequests && <p>Loading events…</p>}
        <ul className="tech-choice-list">
          {requests.map((req) => (
            <li key={req.requestId}>
              <button
                type="button"
                className="tech-choice"
                onClick={() => handleSelectRequest(req)}
                aria-pressed={selectedRequest?.requestId === req.requestId}
              >
                {req.eventName} ({formatMoment(req.eventStart)} – {formatMoment(req.eventEnd)}) —{' '}
                {req.technicalRequirement}
              </button>
            </li>
          ))}
        </ul>
      </Card>

      {selectedRequest && (
        <>
          <Card className="tech-card">
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
          </Card>

          <Card className="tech-card">
            <fieldset className="tech-period">
              <legend>Period to reserve for</legend>
              <div className="field">
                <label htmlFor="period-start">Start</label>
                <input
                  id="period-start"
                  type="datetime-local"
                  value={period.start}
                  onChange={(e) => handlePeriodChange({ ...period, start: e.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="period-end">End</label>
                <input
                  id="period-end"
                  type="datetime-local"
                  value={period.end}
                  onChange={(e) => handlePeriodChange({ ...period, end: e.target.value })}
                />
              </div>
            </fieldset>
            {!periodIsValid && (
              <p role="alert" className="error-text">Please choose an end time after the start time.</p>
            )}

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
          </Card>

          {selectedRequest.status === 'processing' && (
          <Card className="tech-card">
            <h2>Reserve equipment</h2>
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
          </Card>
          )}

          <Card className="tech-card">
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
          </Card>

          <Card className="tech-card">
            <h2>Equipment request status</h2>
            {selectedRequest.status !== 'processing' ? (
              <p>
                Status: <strong>{selectedRequest.status}</strong>
              </p>
            ) : (
              <>
                <p>Current status: <strong>{selectedRequest.status}</strong></p>
                <p>Reserved against requested quantity for the full event period:</p>
                {lines.length === 0 ? (
                  <p>No itemised equipment quantities were requested.</p>
                ) : (
                  <ul>
                    {lines.map((line) => (
                      <li key={line.equipmentId}>
                        {line.equipmentName}: {reservedQuantity(line.equipmentId)} of {line.quantity} reserved
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
                    disabled={!requestDetailsLoaded || updatingStatus
                      || (decision === 'approved' && !allItemsReserved)
                      || (decision === 'rejected' && rejectReason.trim() === '')}
                  >
                    {updatingStatus ? 'Saving…' : `Save ${decision} decision`}
                  </Button>
                </div>
              </>
            )}
          </Card>
        </>
      )}

      {message && <Notice>{message}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
    </section>
  )
}