import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import type { VenueBookingDto } from '../../types/venueBooking'
import { formatEventDateTime } from './formatEventDateTime'
import { useVenueRead } from './useVenueRead'
import '../../components/skeleton.css'
import './BookingApprovalsPage.css'

function requirementsSummary(value: string | null | undefined) {
  const text = value?.trim()
  if (!text) return 'Not specified'
  return text.length > 200 ? `${text.slice(0, 200).trimEnd()}…` : text
}

export function BookingApprovalsPage() {
  const { data, error, loading, retry } = useVenueRead<VenueBookingDto[]>('/venue-staff/booking-requests')
  const [refreshRequested, setRefreshRequested] = useState(false)
  const pending = data?.filter(booking => booking.status === 'pending')
  const refresh = () => { setRefreshRequested(true); retry() }

  return <div className="feature-skeleton booking-approvals-page">
    <div className="feature-skeleton__header">
      <h1>Pending booking requests</h1>
      <Button variant="ghost" onClick={refresh} disabled={loading} aria-label="Refresh pending booking requests">
        <RefreshIcon />{loading && refreshRequested ? 'Refreshing…' : 'Refresh'}
      </Button>
    </div>
    <p className="feature-skeleton__summary">Review the requested venue and event requirements before opening the full booking details.</p>
    {loading ? <p role="status">{refreshRequested ? 'Refreshing pending booking requests…' : 'Loading pending booking requests…'}</p>
      : error ? <Card className="feature-skeleton__body">
        <p role="alert">Could not load pending booking requests. {error}</p>
        <Button variant="secondary" onClick={retry}>Try again</Button>
      </Card>
      : pending?.length === 0 ? <Card className="feature-skeleton__body"><p>No pending booking requests found.</p></Card>
      : <ul className="booking-approvals-list">{pending?.map(booking => <li key={booking.bookingId}>
        <Card className="feature-skeleton__body booking-approvals-card">
          <div className="booking-approvals-card__header">
            <h2>{booking.event.eventName?.trim() || 'Unnamed event'}</h2>
            <span className="feature-skeleton__badge">Status: Pending</span>
          </div>
          <dl>
            <div><dt>Requested venue</dt><dd>{booking.venue.venueAddress?.trim() || 'Not specified'}</dd></div>
            <div><dt>Starts (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.event.startDatetime)}</dd></div>
            <div><dt>Ends (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.event.endDatetime)}</dd></div>
            <div><dt>Expected attendance</dt><dd>{booking.event.expectedAttendance.toLocaleString()} people</dd></div>
            <div><dt>Venue requirements summary</dt><dd>{requirementsSummary(booking.event.venueRequirements)}</dd></div>
          </dl>
          <Link to={`/venue-staff/bookings/${booking.bookingId}?from=booking-approvals`}>View booking details</Link>
        </Card>
      </li>)}</ul>}
  </div>
}
