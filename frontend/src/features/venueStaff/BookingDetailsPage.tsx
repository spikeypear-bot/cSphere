import { BookingAssessment } from '../venueStaff/BookingAssessment'
import { VenueRequirementReviews } from './VenueRequirementReviews'
import { useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { apiClient } from '../../lib/apiClient'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import type { VenueBookingDto, VenueBookingConflict, VenueUnavailabilityConflict } from '../../types/venueBooking'
import { bookingConflictHighlights } from './bookingConflictHighlights'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { EventRequirementsPanel } from './EventRequirementsPanel'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'
import { BookingApproval, BookingInformationHeading } from './BookingApproval'
import { VenueHoldPanel } from './VenueHoldPanel'
import { BookingTimingEditor } from './BookingTimingEditor'
import { BookingDecisionPanel } from './BookingDecisionPanel'
import { formatEventTimeRange } from './formatEventDateTime'
import { BOOKING_STATUS_LABELS, bookingDisplayStatus } from '../../types/venueBookingStatus'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'

/** Shared booking details for Catalogue and the VS02 pending queue. */
export function BookingDetailsPage() {
  const { bookingId } = useParams()
  const pageRef = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const page = pageRef.current
    if (!page || typeof ResizeObserver === 'undefined') return
    let height = 0
    const preserveHeight = () => {
      const next = page.getBoundingClientRect().height
      if (next > height) { height = next; page.style.minHeight = `${height}px` }
    }
    preserveHeight()
    const observer = new ResizeObserver(preserveHeight)
    observer.observe(page)
    return () => { observer.disconnect(); page.style.minHeight = '' }
  }, [bookingId])
  const [timingRevision, setTimingRevision] = useState(0)
  const [holdFormFor, setHoldFormFor] = useState<string | null>(null)
  const [conflictState, setConflictState] = useState<{ bookingId?: string; items: VenueBookingConflict[]; periods: VenueUnavailabilityConflict[] }>({ bookingId, items: [], periods: [] })
  if (conflictState.bookingId !== bookingId) setConflictState({ bookingId, items: [], periods: [] })
  const conflicts = conflictState.bookingId === bookingId ? conflictState.items : []
  const unavailablePeriods = conflictState.bookingId === bookingId ? conflictState.periods : []
  const setConflicts = (items: VenueBookingConflict[], periods: VenueUnavailabilityConflict[]) => setConflictState({ bookingId, items, periods })
  const [searchParams] = useSearchParams()
  const fromApprovals = searchParams.get('from') === 'booking-approvals'
  const { data: booking, error, retry, loading, replaceData, failRead } = useVenueRead<VenueBookingDto>(`/venue-bookings/${bookingId}`, !fromApprovals, 5000, true)
  const expiredHolds = useHoldDeadline(booking?.holds ?? NO_HOLDS, retry)
  const data = booking ? { ...booking, status: booking.status ?? 'pending' } : undefined
  const highlights = data?.status === 'pending' ? [...conflicts, ...unavailablePeriods].map(conflict => bookingConflictHighlights(data.timing ?? data.event, conflict)) : []
  return <div ref={pageRef} className="feature-skeleton booking-details-page">
    <Link to={fromApprovals ? '/venue-staff/booking-approvals' : data ? `/venue-staff/catalogue/${data.venue.venueId}` : '/venue-staff/catalogue'}>
      {fromApprovals ? 'Back to Booking Approvals' : data ? 'Back to venue details' : 'Back to venue catalogue'}
    </Link>
    <h1>Booking details</h1>
    {data?.requiresAlternative && data.status !== 'attention' && <p role="status" className="unavailability-warning">Alternative arrangements required. This booking is affected by venue unavailability; the event has not been cancelled.</p>}
    {data?.replacesBookingId && <p>This is a replacement request. <Link to={`/venue-staff/bookings/${data.replacesBookingId}`}>View original booking</Link></p>}
    {error && <div><p role="alert">{error}{data && " Showing the previously loaded booking; the requested booking could not be loaded."}</p><Button onClick={retry}>Try again</Button></div>}
    {!data ? (error ? null : <p role="status">Loading booking details…</p>)
      : <>
        {data.status === 'rejected' && (data.rejectReason?.trim() || data.alternativeVenueAddress?.trim() || data.alternativeArrangement?.trim()) &&
          <BookingDecisionPanel className="booking-rejection-details" role="status" aria-label="Rejection reason">
            <h3>Rejection Reason</h3>
            {data.rejectReason?.trim() && <p>{data.rejectReason}</p>}
            {(data.alternativeVenueAddress?.trim() || data.alternativeArrangement?.trim()) && <dl>
              {data.alternativeVenueAddress?.trim() && <><dt>Suggested Alternative Venue:</dt><dd>{data.alternativeVenueAddress}</dd></>}
              {data.alternativeArrangement?.trim() && <><dt>Alternative Arrangement:</dt><dd>{data.alternativeArrangement}</dd></>}
            </dl>}
          </BookingDecisionPanel>}
        {!loading && !error && <VenueHoldPanel key={data.bookingId} bookingId={data.bookingId} booking={data}
          pending={data.status === 'pending'} initialHolds={data.holds ?? NO_HOLDS} creationOpen={holdFormFor === data.bookingId}
          onCancel={() => setHoldFormFor(null)} onCreated={() => setHoldFormFor(null)} onConflicts={setConflicts}
          onChanged={async updated => {
            if (updated) replaceData(updated)
            const current = await apiClient.get<VenueBookingDto>(`/venue-bookings/${data.bookingId}`, { cache: 'no-store' })
            replaceData(current)
          }} />}
        <section aria-label="Booking identity" className="booking-context-section">
          <h2>Booking identity</h2>
          <Card className="feature-skeleton__body booking-context-card">
            <dl className="venue-card-preview__information booking-identity-fields">
              <div className="booking-identity-fields__event"><dt>Event:</dt>{' '}<dd>{data.event.eventName}</dd></div>
              <div><dt>Event ID:</dt>{' '}<dd className="booking-context-card__id">{data.event.eventId}</dd></div>
              <div><dt>Booking ID:</dt>{' '}<dd className="booking-context-card__id">{data.bookingId}</dd></div>
              <div><dt>Current coordinator:</dt><dd>{data.currentCoordinatorName ?? 'Unassigned'}{data.currentCoordinatorEmail ? ` (${data.currentCoordinatorEmail})` : ''}</dd></div>
            </dl>
          </Card>
        </section>
        <section aria-label="Other bookings for this event" className="booking-context-section">
          <h2>Other bookings for this event</h2>
          {!data.relatedBookings?.length ? <p className="booking-context-section__empty">No other bookings for this event.</p> : <ul className="booking-related-list">
            {data.relatedBookings.map(related => <li key={related.bookingId}>
              <Card className="feature-skeleton__body booking-context-card">
                <div className="booking-related-card__header">
                  <h3>{related.venueAddress}</h3>
                  <span className="venue-card-preview__tag booking-status-tag" data-booking-status={related.status}>{BOOKING_STATUS_LABELS[related.status]}</span>
                </div>
                <p className="venue-card-preview__id booking-related-card__id">Booking ID: {related.bookingId}</p>
                <dl className="venue-card-preview__information booking-related-card__timings">
                  <div><dt>Booking window (SGT)</dt><dd>{formatEventTimeRange(related.startDatetime, related.endDatetime)}</dd></div>
                  <div><dt>Venue occupied (SGT)</dt><dd>{formatEventTimeRange(related.effectiveStart, related.effectiveEnd)}</dd></div>
                </dl>
              </Card>
            </li>)}
          </ul>}
        </section>
        <BookingAssessment bookingId={data.bookingId} onChanged={retry} />
        <VenueRequirementReviews key={`reviews:${data.bookingId}`} bookingId={data.bookingId} />
        {!loading && !error && data.bookingId === bookingId
          ? <BookingApproval key={`${bookingId}:${timingRevision}`} booking={data} onApproved={replaceData} onUnavailable={failRead}
              conflicts={conflicts} unavailablePeriods={unavailablePeriods} onConflicts={setConflicts}
              onDecisionStarted={() => setHoldFormFor(null)}
              onHoldRequested={() => setHoldFormFor(data.bookingId)} />
          : <div className="booking-details-page__section-header"><BookingInformationHeading /></div>}
        <div className="booking-detail-layout">
          <section aria-labelledby="venue-information-heading">
            <VenueDetailsPanel venue={data.venue} />
          </section>
          <div className="booking-requirements-column" aria-busy={loading}>
            {loading && <span className="booking-loading-notice" role="status">Loading next booking…</span>}
            <section aria-labelledby="event-requirements-heading">
              <EventRequirementsPanel requirements={data.effectiveRequirements ?? data.requirements} event={data.event} timing={data.timing} status={bookingDisplayStatus(data.status, data.holds, expiredHolds)}
                highlightStart={highlights.some(h => h.requestStart)} highlightEnd={highlights.some(h => h.requestEnd)} />
            </section>
            {(data.bookingNotes || data.suitabilityNote) && <CoordinatorNotes booking={data} />}
            <BookingTimingEditor key={data.bookingId} booking={data} onSaved={saved => {
              replaceData(saved); setConflicts([], []); setTimingRevision(value => value + 1)
            }} disabled={loading || !!error || bookingDisplayStatus(data.status, data.holds, expiredHolds) === 'tentative'} />
            {!fromApprovals && <BookingPagination venueId={data.venue.venueId} bookingId={data.bookingId} busy={loading || !!error} />}
          </div>
        </div>
      </>}
  </div>
}

/** EC03: what the Event Coordinator wrote when requesting this venue. */
function CoordinatorNotes({ booking }: { booking: VenueBookingDto }) {
  return <section aria-labelledby="coordinator-notes-heading" className="booking-coordinator-notes">
    <h2 id="coordinator-notes-heading">From the Event Coordinator</h2>
    {booking.suitabilityNote && <p className="booking-coordinator-notes__warning">
      <strong>Does not meet every accessibility requirement.</strong> Coordinator's justification: {booking.suitabilityNote}
    </p>}
    {booking.bookingNotes && <p>{booking.bookingNotes}</p>}
  </section>
}

function BookingPagination({ venueId, bookingId, busy }: { venueId: string; bookingId: string; busy: boolean }) {
  const navigate = useNavigate()
  const { data, error, retry } = useVenueRead<VenueBookingDto[]>(`/venues/${venueId}/bookings`)
  if (error) return <div><p role="alert">Could not load booking navigation: {error}</p><Button onClick={retry}>Retry booking navigation</Button></div>
  if (!data) return <p role="status">Loading booking navigation…</p>
  const index = data.findIndex(booking => booking.bookingId === bookingId)
  if (index < 0) return <p>This booking is no longer in the venue’s booking list.</p>
  const previousDisabled = busy || index === 0
  const nextDisabled = busy || index === data.length - 1
  return <nav className="booking-pagination" aria-label="Venue bookings">
    <Button type="button" variant="secondary" aria-disabled={previousDisabled}
      onClick={() => { if (!previousDisabled) void navigate(`/venue-staff/bookings/${data[index - 1].bookingId}`) }}>Previous</Button>
    <span aria-live="polite">Booking {index + 1} of {data.length}</span>
    <Button type="button" variant="secondary" aria-disabled={nextDisabled}
      onClick={() => { if (!nextDisabled) void navigate(`/venue-staff/bookings/${data[index + 1].bookingId}`) }}>Next</Button>
  </nav>
}
