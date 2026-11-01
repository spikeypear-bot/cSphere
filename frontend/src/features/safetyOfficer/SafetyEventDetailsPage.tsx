import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { EVENT_STATUS_LABELS, isPlanningEditable, type EventDto } from '../../types/event'
import type { VenueDto } from '../../types/venue'
import type { EventVenueBookingDto } from '../../types/venueBookingRequest'
import { useSafetyData } from './useSafetyData'
import { ReadinessChecklistPanel } from './ReadinessChecklistPanel'
import { SafetyDecisionSummary } from './SafetyDecisionSummary'
import type { ReadinessDto, ReadinessItem } from './readinessTypes'
import './safetyOfficer.css'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'

interface EquipmentRequest {
  requestId: string
  status: string
  technicalRequirement: string
  rejectReason: string | null
  lines: { equipmentId: string; equipmentName: string; quantity: number }[]
}

interface EquipmentReservation {
  logId: string
  equipmentName: string
  quantity: number
  serialNumber: string | null
  loanedFrom: string
  loanedUntil: string
}

export function SafetyEventDetailsPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const eventState = useSafetyData<EventDto>(eventId ? `/safety/events/${eventId}` : null)
  const readinessState = useSafetyData<ReadinessDto>(eventId ? `/safety/events/${eventId}/readiness` : null)
  const event = eventState.data
  const bookingState = useSafetyData<EventVenueBookingDto[]>(event ? `/events/${event.eventId}/venue-bookings` : null)
  const equipmentState = useSafetyData<EquipmentRequest[]>(event ? `/events/${event.eventId}/equipment-requests` : null)
  const reservationsState = useSafetyData<EquipmentReservation[]>(
    event ? `/equipment/events/${event.eventId}/reservations` : null,
  )
  const [savingItem, setSavingItem] = useState<string | null>(null)
  const [reviewNote, setReviewNote] = useState('')
  // SO-C5/SO-C6: the checklist items a rejection or request for changes concerns.
  const [flaggedItems, setFlaggedItems] = useState<string[]>([])
  const [actionError, setActionError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const readiness = readinessState.data

  async function saveItem(item: ReadinessItem, checked: boolean, note: string) {
    if (!eventId) return
    setSavingItem(item.itemKey)
    setActionError(null)
    try {
      const result = await apiClient.patch<ReadinessDto>(
        `/safety/events/${eventId}/readiness/items/${item.itemKey}`, { checked, note },
      )
      readinessState.setData(result)
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not save this checklist item.')
    } finally {
      setSavingItem(null)
    }
  }

  async function review(decision: 'approve' | 'request_changes' | 'reject') {
    if (!eventId) return
    setReviewing(true)
    setActionError(null)
    try {
      const result = await apiClient.post<ReadinessDto>(`/safety/events/${eventId}/approval`, {
        decision,
        note: reviewNote,
        flaggedItems: decision === 'approve' ? [] : flaggedItems,
      })
      readinessState.setData(result)
      eventState.reload()
      setReviewNote('')
      setFlaggedItems([])
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not save the safety review.')
      // The event may have been decided meanwhile (e.g. by another Safety
      // Officer): show its current state. What was typed is kept.
      eventState.reload()
      readinessState.reload()
    } finally {
      setReviewing(false)
    }
  }

  if (eventState.error) return <p role="alert">Could not load this event. {eventState.error}</p>
  if (!event) return <p role="status">Loading event…</p>

  // EC08: the checklist can be worked on until a decision is made; a
  // decision is possible once the coordinator has sent the event for review.
  const checklistEditable = isPlanningEditable(event.status) || event.status === 'awaiting_safety_review'
  const awaitingDecision = event.status === 'awaiting_safety_review'
  const noteGiven = reviewNote.trim() !== ''
  const canSendBack = noteGiven && flaggedItems.length > 0
  const toggleFlag = (key: string, on: boolean) => setFlaggedItems((current) =>
    on ? [...current, key] : current.filter((k) => k !== key))
  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/safety-officer/reviews">Safety reviews</Link>}
        title={event.eventName}
        description={`${EVENT_STATUS_LABELS[event.status] ?? event.status} · ${new Date(event.startDatetime).toLocaleString()}`}
      />
      <Card className="safety-panel">
        <h2>Event details</h2>
        <dl className="safety-details-grid">
          <div><dt>Purpose</dt><dd>{event.purpose}</dd></div>
          <div><dt>Description</dt><dd>{event.description || 'Not provided'}</dd></div>
          <div><dt>Start</dt><dd>{new Date(event.startDatetime).toLocaleString()}</dd></div>
          <div><dt>End</dt><dd>{new Date(event.endDatetime).toLocaleString()}</dd></div>
          <div><dt>Expected attendance</dt><dd>{event.expectedAttendance.toLocaleString()}</dd></div>
          <div><dt>Accessibility needs</dt><dd>{event.accessibilityNeeds.length ? event.accessibilityNeeds.join(', ') : 'None specified'}</dd></div>
          <div><dt>Registration</dt><dd>{event.registrationNeeds === null ? 'Not finalised' : event.registrationNeeds ? 'Required' : 'Not required'}</dd></div>
        </dl>
      </Card>

      <Card className="safety-panel">
        <h2>Venues</h2>
        {bookingState.error && <p role="alert">Could not load booking details. {bookingState.error}</p>}
        {bookingState.loading && <p role="status">Loading booking details…</p>}
        {bookingState.data?.filter(b => ['pending', 'approved', 'attention'].includes(b.status)).map(booking =>
          <VenueArrangement key={booking.bookingId} booking={booking} event={event} />)}
        {bookingState.data?.length === 0 && <p>No venue booking has been submitted.</p>}
      </Card>

      <Card className="safety-panel">
        <h2>Equipment</h2>
        {equipmentState.error ? <p role="alert">Could not load equipment requests. {equipmentState.error}</p> : null}
        {equipmentState.loading && !equipmentState.data ? <p role="status">Loading equipment requests…</p> : null}
        {equipmentState.data?.length === 0 ? <p>No equipment request has been submitted.</p> : null}
        {equipmentState.data?.map((request) => (
          <div key={request.requestId} className="safety-resource">
            <h3>Request: {request.status}</h3>
            <p>{request.technicalRequirement || 'No additional requirements.'}</p>
            {request.rejectReason ? <p>Reason: {request.rejectReason}</p> : null}
            <ul>{request.lines.map((line) => <li key={line.equipmentId}>{line.equipmentName} × {line.quantity}</li>)}</ul>
          </div>
        ))}
        {reservationsState.error ? <p role="alert">Could not load equipment reservations. {reservationsState.error}</p> : null}
        {reservationsState.loading && !reservationsState.data ? <p role="status">Loading equipment reservations…</p> : null}
        {reservationsState.data?.length === 0 ? <p>No equipment has been reserved yet.</p> : null}
        {reservationsState.data?.map((reservation) => (
          <p key={reservation.logId}>
            Reserved: {reservation.equipmentName} × {reservation.quantity}
            {reservation.serialNumber ? ` (${reservation.serialNumber})` : ''}
          </p>
        ))}
      </Card>

      {readinessState.error ? <p role="alert">Could not load readiness checklist. {readinessState.error}</p> : null}
      {readiness ? (
        <ReadinessChecklistPanel
          readiness={readiness}
          editable={checklistEditable}
          savingItem={savingItem}
          onSave={saveItem}
        />
      ) : readinessState.loading ? <p role="status">Loading readiness checklist…</p> : null}

      {readiness ? (
        <Card className="safety-panel">
          <h2>Safety approval</h2>
          <h3>{awaitingDecision && readiness.approvalStatus !== 'not_reviewed' ? 'Previous decision' : 'Latest decision'}</h3>
          <SafetyDecisionSummary readiness={readiness} />
          {isPlanningEditable(event.status) ? (
            <p role="status">Waiting for the Event Coordinator to send this event for its safety check.
              You can work on the checklist in the meantime.</p>
          ) : null}
          {event.status === 'safety_approved' ? (
            <p role="status">Approved. Waiting for the Event Coordinator to confirm the event.</p>
          ) : null}
          {awaitingDecision ? (
            <>
              <label htmlFor="safety-review-note">Note for the Event Coordinator</label>
              <textarea id="safety-review-note" maxLength={2000} value={reviewNote} onChange={(e) => setReviewNote(e.target.value)}
                aria-describedby="safety-review-note-hint" />
              <p id="safety-review-note-hint" className="field-hint">
                Optional when approving. Required to request changes or reject, so the coordinator knows what to fix.
              </p>
              <fieldset className="safety-flags" aria-describedby="safety-flags-hint">
                <legend>Checklist items this decision concerns</legend>
                <p id="safety-flags-hint" className="field-hint">
                  Select at least one to request changes or reject. Not used when approving.
                </p>
                {readiness.items.map((item) => (
                  <label key={item.itemKey} className="safety-flags__option">
                    <input type="checkbox" checked={flaggedItems.includes(item.itemKey)} disabled={reviewing}
                      aria-label={`Raise: ${item.label}`}
                      onChange={(e) => toggleFlag(item.itemKey, e.target.checked)} />
                    <span>{item.label}</span>
                  </label>
                ))}
              </fieldset>
              <div className="safety-actions">
                <Button disabled={reviewing || readiness.completedItems !== readiness.totalItems}
                  onClick={() => void review('approve')}>
                  {reviewing ? 'Saving…' : 'Approve'}
                </Button>
                <Button variant="secondary" disabled={reviewing || !canSendBack} onClick={() => void review('request_changes')}>
                  Request changes
                </Button>
                <Button variant="secondary" disabled={reviewing || !canSendBack} onClick={() => void review('reject')}>
                  Reject
                </Button>
              </div>
              {readiness.completedItems !== readiness.totalItems ? (
                <p>Approval is blocked until all checklist items are complete.</p>
              ) : null}
            </>
          ) : null}
        </Card>
      ) : null}
      {actionError ? <p role="alert">{actionError}</p> : null}
      <Link to="/safety-officer/reviews" className="button button--secondary">Back to events</Link>
    </div>
  )
}

function VenueArrangement({ booking, event }: { booking: EventVenueBookingDto; event: EventDto }) {
  const venueState = useSafetyData<VenueDto>(`/venues/${booking.venueId}`)
  const requirements = booking.requirements
  return <section className="safety-resource">
    <h3><span>{booking.venueAddress ?? 'Venue'}</span> — {booking.status}</h3>
    {venueState.error && <p role="alert">Could not load venue details. {venueState.error}</p>}
    <dl className="safety-details-grid">
      <div><dt>Capacity</dt><dd>{booking.venueCapacity ?? 'Not finalised'}</dd></div>
      <div><dt>Expected attendance in this room</dt><dd>{requirements?.expectedAttendance ?? event.expectedAttendance}</dd></div>
      <div><dt>Required facilities</dt><dd>{(requirements?.requiredFacilities ?? event.requiredFacilities ?? []).join(', ') || 'None specified'}</dd></div>
      <div><dt>Accessibility needs</dt><dd>{(requirements?.accessibilityNeeds ?? event.accessibilityNeeds).join(', ') || 'None specified'}</dd></div>
      <div><dt>Venue requirements</dt><dd>{requirements?.venueRequirements ?? event.venueRequirements}</dd></div>
      <div><dt>Available facilities</dt><dd>{venueState.data?.venueFacilities.join(', ') || 'None listed'}</dd></div>
      <div><dt>Available accessibility</dt><dd>{venueState.data?.venueAccessibilities.join(', ') || 'None listed'}</dd></div>
    </dl>
    <BookingTimingSummary timing={booking.timing} />
    {booking.requiresAlternative && <p role="alert">This booking needs an alternative venue arrangement.</p>}
  </section>
}
