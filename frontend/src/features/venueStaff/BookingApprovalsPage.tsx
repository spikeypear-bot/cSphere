import { useMemo, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RowLink } from '../../components/ui/RowLink'
import { StatusFilter } from '../../components/ui/JourneyStatus'
import { BOOKING_STATUS_LABELS, bookingDisplayStatus } from '../../types/venueBookingStatus'
import { useHoldDeadline } from './useHoldDeadline'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import type { VenueBookingDto } from '../../types/venueBooking'
import { formatEventDateTime, formatEventTimeRange } from './formatEventDateTime'
import { useVenueRead } from './useVenueRead'
import './BookingApprovalsPage.css'
import { venueFacilityLabels, venueAccessibilityLabels, type Facility } from '../../types/venue'
import type { AccessibilityFeature } from '../../types/eventRequest'

function requirementsSummary(value: string | null | undefined) {
  const text = value?.trim()
  if (!text) return 'Not specified'
  return text.length > 200 ? `${text.slice(0, 200).trimEnd()}…` : text
}

export function BookingApprovalsPage() {
  const { data, error, loading, retry } = useVenueRead<VenueBookingDto[]>('/venue-staff/booking-requests', false, 5000)
  const [refreshRequested, setRefreshRequested] = useState(false)
  const [filter, setFilter] = useState<string | null>(null)
  const holds = useMemo(() => (data ?? []).flatMap(b => b.holds ?? []), [data])
  const expired = useHoldDeadline(holds, retry)
  const requests = (data ?? []).filter(b => b.status === 'pending' || b.status === 'attention')
  const attention = requests.filter(b => b.status === 'attention')
  const status = (b: VenueBookingDto) => bookingDisplayStatus(b.status, b.holds, expired)
  const pending = requests.filter(b => status(b) === 'pending')
  const tentative = requests.filter(b => status(b) === 'tentative')
  const refresh = () => { setRefreshRequested(true); retry() }

  return <div className="page booking-approvals-page">
    <PageHeader
      title="Booking approvals"
      description="Review the requested venue and event requirements before opening the full booking details."
      actions={
        <Button variant="ghost" onClick={refresh} disabled={loading} aria-label="Refresh pending booking requests">
          <RefreshIcon />{loading && refreshRequested ? 'Refreshing…' : 'Refresh'}
        </Button>
      }
    />
    {loading ? <p role="status">{refreshRequested ? 'Refreshing pending booking requests…' : 'Loading pending booking requests…'}</p>
      : error ? <Card className="dash-row-card">
        <p role="alert">Could not load pending booking requests. {error}</p>
        <Button variant="secondary" onClick={retry}>Try again</Button>
      </Card>
      : <>
        {(filter === null || filter === 'attention') && attention.length > 0 && <section className="booking-approvals-section">
          <h2>Bookings Requiring Attention</h2><BookingRequestList bookings={attention} />
        </section>}
        <StatusFilter statuses={requests.map(b => ({ code: status(b), label: BOOKING_STATUS_LABELS[status(b)] }))}
          selected={filter} onChange={setFilter} label="Filter booking approvals by status" showSingle />
        {(filter === null || filter === 'pending') && <section className="booking-approvals-section" aria-labelledby="pending-bookings-heading">
          <h2 id="pending-bookings-heading">Pending booking requests</h2>
          <p>Requests awaiting a decision. Approve, reject, or put a request on tentative hold.</p>
          {!pending.length ? <Card className="dash-row-card"><p>No pending booking requests found.</p></Card>
            : <BookingRequestList bookings={pending} />}
        </section>}
        {(filter === null || filter === 'tentative') && <section className="booking-approvals-section" aria-labelledby="tentative-bookings-heading">
          <h2 id="tentative-bookings-heading">Tentative venue holds</h2>
          <p>Reserved temporarily while arrangements are finalised. Open a booking to release or convert its hold.</p>
          {!tentative.length ? <Card className="dash-row-card"><p>No active tentative venue holds.</p></Card>
            : <BookingRequestList bookings={tentative} held />}
        </section>}
      </>}
  </div>
}

function BookingRequestList({ bookings, held = false }: { bookings: VenueBookingDto[]; held?: boolean }) {
  return <ul className="booking-approvals-list">{bookings.map(booking => {
    const status = held ? 'tentative' : booking.status
    const hold = held ? booking.holds?.find(h => h.status === 'active') : undefined
    return <li key={booking.bookingId}>
      {/* The whole card opens the booking; decisions are made there. */}
      <RowLink to={`/venue-staff/bookings/${booking.bookingId}?from=booking-approvals`} className="card dash-row-card booking-approvals-card"
        titleAs="h3" title={booking.event.eventName?.trim() || 'Unnamed event'}
        badges={<span className="venue-card-preview__tag booking-status-tag" data-booking-status={status}>Status: {BOOKING_STATUS_LABELS[status]}</span>}>
        <dl className="dash-figures dash-facts">
          <div><dt>Event ID</dt><dd>{booking.event.eventId}</dd></div>
          <div><dt>Booking ID</dt><dd>{booking.bookingId}</dd></div>
          <div><dt>Current coordinator</dt><dd>{booking.currentCoordinatorName ?? 'Unassigned'}{booking.currentCoordinatorEmail ? ` (${booking.currentCoordinatorEmail})` : ''}</dd></div>
          <div><dt>Requested venue</dt><dd>{booking.venue.venueAddress?.trim() || 'Not specified'}</dd></div>
          <div><dt>Starts (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.timing?.startDatetime ?? booking.event.startDatetime)}</dd></div>
          <div><dt>Ends (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.timing?.endDatetime ?? booking.event.endDatetime)}</dd></div>
          {booking.timing && <div><dt>Venue Occupied (SGT)</dt><dd>{formatEventTimeRange(booking.timing.effectiveStart, booking.timing.effectiveEnd)}</dd></div>}
          {hold && <div><dt>Hold expiry (SGT)</dt><dd>{formatEventDateTime(hold.expiresAt)}</dd></div>}
          <div><dt>Expected attendance</dt><dd>{(booking.effectiveRequirements?.expectedAttendance ?? booking.requirements?.expectedAttendance ?? booking.event.expectedAttendance).toLocaleString()} people</dd></div>
          <div><dt>Venue requirements summary</dt><dd>{requirementsSummary(booking.effectiveRequirements?.venueRequirements ?? booking.requirements?.venueRequirements ?? booking.event.venueRequirements)}</dd></div>
          <div><dt>Required facilities</dt><dd>{(booking.effectiveRequirements?.requiredFacilities ?? booking.requirements?.requiredFacilities ?? []).map(f => venueFacilityLabels[f as Facility] ?? f).join(', ') || 'None specified'}</dd></div>
          <div><dt>Accessibility requirements</dt><dd>{(booking.effectiveRequirements?.accessibilityNeeds ?? booking.requirements?.accessibilityNeeds ?? booking.event.accessibilityNeeds).map(f => venueAccessibilityLabels[f as AccessibilityFeature] ?? f).join(', ') || 'None specified'}</dd></div>
        </dl>
      </RowLink>
    </li>
  })}</ul>
}
