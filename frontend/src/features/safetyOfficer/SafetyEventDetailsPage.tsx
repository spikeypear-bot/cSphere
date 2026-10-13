import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { EVENT_STATUS_LABELS, type EventDto } from '../../types/event'
import type { VenueDto } from '../../types/venue'
import type { EventVenueBookingDto } from '../../types/venueBookingRequest'
import { useSafetyData } from './useSafetyData'
import { ReadinessChecklistPanel } from './ReadinessChecklistPanel'
import type { ReadinessDto, ReadinessItem } from './readinessTypes'
import './safetyOfficer.css'

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
  const venueState = useSafetyData<VenueDto>(event?.venueId ? `/venues/${event.venueId}` : null)
  const bookingState = useSafetyData<EventVenueBookingDto[]>(event ? `/events/${event.eventId}/venue-bookings` : null)
  const equipmentState = useSafetyData<EquipmentRequest[]>(event ? `/events/${event.eventId}/equipment-requests` : null)
  const reservationsState = useSafetyData<EquipmentReservation[]>(
    event ? `/equipment/events/${event.eventId}/reservations` : null,
  )
  const [savingItem, setSavingItem] = useState<string | null>(null)
  const [reviewNote, setReviewNote] = useState('')
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

  async function review(decision: 'approve' | 'reject') {
    if (!eventId) return
    setReviewing(true)
    setActionError(null)
    try {
      const result = await apiClient.post<ReadinessDto>(`/safety/events/${eventId}/approval`, {
        decision,
        note: reviewNote,
      })
      readinessState.setData(result)
      eventState.reload()
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not save the safety review.')
    } finally {
      setReviewing(false)
    }
  }

  if (eventState.error) return <p role="alert">Could not load this event. {eventState.error}</p>
  if (!event) return <p role="status">Loading event…</p>

  const isPlanning = event.status === 'pending'
  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/safety-officer">Safety Officer console</Link>}
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
        <h2>Venue</h2>
        {!event.venueId ? <p>Venue not yet finalised.</p> : null}
        {venueState.error ? <p role="alert">Could not load venue details. {venueState.error}</p> : null}
        {venueState.loading && !venueState.data ? <p role="status">Loading venue details…</p> : null}
        {venueState.data ? (
          <dl className="safety-details-grid">
            <div><dt>Venue</dt><dd>{venueState.data.venueAddress}</dd></div>
            <div><dt>Capacity</dt><dd>{venueState.data.venueCapacity?.toLocaleString() ?? 'Not finalised'}</dd></div>
            <div><dt>Facilities</dt><dd>{venueState.data.venueFacilities.length ? venueState.data.venueFacilities.join(', ') : 'None listed'}</dd></div>
            <div><dt>Accessibility</dt><dd>{venueState.data.venueAccessibilities.length ? venueState.data.venueAccessibilities.join(', ') : 'None listed'}</dd></div>
          </dl>
        ) : null}
        <p>Booking status: {bookingState.error ? `Could not load booking details. ${bookingState.error}` :
          bookingState.loading ? 'Loading…' : bookingState.data?.length
            ? bookingState.data.map((booking) => `${booking.venueAddress ?? 'Venue'} — ${booking.status}`).join('; ')
            : 'No venue booking has been submitted.'}</p>
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
          editable={isPlanning}
          savingItem={savingItem}
          onSave={saveItem}
        />
      ) : readinessState.loading ? <p role="status">Loading readiness checklist…</p> : null}

      {readiness ? (
        <Card className="safety-panel">
          <h2>Safety approval</h2>
          <p>Current approval: {readiness.approvalStatus.replace('_', ' ')}</p>
          {readiness.approvalNote ? <p>Note: {readiness.approvalNote}</p> : null}
          {readiness.reviewedBy ? <p>Reviewed by {readiness.reviewedBy}{readiness.reviewedAt ? ` · ${new Date(readiness.reviewedAt).toLocaleString()}` : ''}</p> : null}
          {isPlanning ? (
            <>
              <label htmlFor="safety-review-note">Reason or approval note (optional)</label>
              <textarea id="safety-review-note" maxLength={2000} value={reviewNote} onChange={(e) => setReviewNote(e.target.value)} />
              <div className="safety-actions">
                <Button disabled={reviewing || readiness.completedItems !== readiness.totalItems}
                  onClick={() => void review('approve')}>
                  {reviewing ? 'Saving…' : 'Approve and confirm'}
                </Button>
                <Button variant="secondary" disabled={reviewing} onClick={() => void review('reject')}>
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
      <Link to="/safety-officer" className="button button--secondary">Back to events</Link>
    </div>
  )
}
