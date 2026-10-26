import { BOOKING_STATUS_LABELS } from '../../types/venueBookingStatus'
import { useEffect, useRef, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueBookingDto, VenueBookingConflict, VenueUnavailabilityConflict } from '../../types/venueBooking'
import type { VenueDto } from '../../types/venue'
import { formatEventDateTime, formatEventTimeRange } from './formatEventDateTime'
import { BookingConflictCard, UnavailabilityConflictCard } from './BookingConflictCard'
import { BookingDecisionPanel } from './BookingDecisionPanel'

const MAX_ALTERNATIVE_ARRANGEMENT_LENGTH = 2000

export function BookingApproval({ booking, onApproved, onUnavailable, conflicts, unavailablePeriods, onConflicts, onHoldRequested, onDecisionStarted }: {
  booking: VenueBookingDto
  onApproved: (booking: VenueBookingDto) => void
  onUnavailable: (message: string) => void
  conflicts: VenueBookingConflict[]
  unavailablePeriods: VenueUnavailabilityConflict[]
  onConflicts: (conflicts: VenueBookingConflict[], unavailablePeriods: VenueUnavailabilityConflict[]) => void
  onDecisionStarted?: () => void
  onHoldRequested?: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [venues, setVenues] = useState<VenueDto[] | null>(null)
  const [venueOptionsError, setVenueOptionsError] = useState<string>()
  const [loadingVenues, setLoadingVenues] = useState(false)
  const [alternativeVenueId, setAlternativeVenueId] = useState('')
  const [alternativeArrangement, setAlternativeArrangement] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [approvalBlocked, setApprovalBlocked] = useState(false)
  const submitting = useRef(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])

  async function loadAlternativeVenues() {
    setLoadingVenues(true)
    setVenueOptionsError(undefined)
    try {
      const available = await apiClient.get<VenueDto[]>('/venues')
      if (!Array.isArray(available)) {
        throw new Error('The venue list response was invalid. You can still reject without a venue suggestion.')
      }
      if (active.current) setVenues(available)
    } catch (cause) {
      if (active.current) {
        setVenueOptionsError(cause instanceof ApiClientError
          ? cause.message
          : cause instanceof Error
            ? cause.message
            : 'Could not load alternative venues. You can still reject this request without a venue suggestion.')
      }
    } finally {
      if (active.current) setLoadingVenues(false)
    }
  }

  async function readCurrentBooking() {
    try {
      const current = await apiClient.get<VenueBookingDto>('/venue-bookings/' + booking.bookingId, { cache: 'no-store' })
      if (active.current) {
        onApproved(current)
        setNeedsRefresh(false)
        setError(previous => previous?.replace(' Checking the latest booking status.', ' Latest booking status: ' + BOOKING_STATUS_LABELS[current.status] + '.'))
        return true
      }
    } catch (cause) {
      if (!active.current) return
      if (cause instanceof ApiClientError && cause.status === 404) {
        onUnavailable('This booking could not be found. It may have been removed. Return to the pending requests or try again.')
      } else {
        setError(previous => (previous ? previous + ' ' : '') + 'Could not refresh booking details. Refresh before attempting a booking decision again.')
        setNeedsRefresh(true)
      }
    }
    return false
  }

  async function refresh() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    setError(undefined)
    onConflicts([], [])
    if (await readCurrentBooking()) setApprovalBlocked(false)
    submitting.current = false
    if (active.current) setBusy(false)
  }

  async function decide(action: 'approve' | 'reject') {
    if (action === 'reject' && !reason.trim()) { setReasonError('A rejection reason is required.'); return }
    if (submitting.current || needsRefresh || booking.status !== 'pending' || (action === 'approve' && approvalBlocked)) return
    submitting.current = true
    setBusy(true)
    setError(undefined)
    onConflicts([], [])
    try {
      const updated = await apiClient.patch<VenueBookingDto>('/venue-bookings/' + booking.bookingId + '/' + action, action === 'reject' ? {
        reason: reason.trim(),
        alternativeVenueId: alternativeVenueId || null,
        alternativeArrangement: alternativeArrangement.trim() || null,
      } : undefined)
      if (active.current) {
        setConfirming(false)
        setRejecting(false)
        onApproved(updated)
      }
    } catch (cause) {
      if (active.current) {
        setConfirming(false)
        setRejecting(false)
        setNeedsRefresh(true)
        const message = cause instanceof ApiClientError ? cause.message : 'Could not confirm the booking decision result.'
        const unavailable = action === 'approve' && cause instanceof ApiClientError && cause.status === 409 && cause.unavailablePeriods.length > 0
        if (unavailable) {
          setApprovalBlocked(true)
          setError(cause.conflicts.length > 0
            ? 'Approval is blocked by the following approved bookings and recorded unavailable periods during the event, setup or turnaround period. All times are Singapore time, UTC+08:00.'
            : 'Approval is blocked by the following recorded unavailable periods during the event, setup or turnaround period. All times are Singapore time, UTC+08:00.')
        } else setError(message + ' Checking the latest booking status.')
        if (action === 'approve' && cause instanceof ApiClientError && cause.status === 409) {
          onConflicts(cause.conflicts, cause.unavailablePeriods)
        } else onConflicts([], [])
        // A lost response may follow a committed approval. Only a fresh backend read
        // can establish the outcome; never infer success or allow a blind retry.
        await readCurrentBooking()
      }
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }

  return <section className="booking-approval" aria-label="Booking approval" aria-busy={busy}>
    {rejecting && booking.status === 'pending' && <BookingDecisionPanel as="form" aria-labelledby="reject-booking-heading"
      onSubmit={event => { event.preventDefault(); void decide('reject') }}>
      <h3 id="reject-booking-heading">Confirm booking rejection</h3>
      <div className={reasonError ? 'field field--invalid' : 'field'}>
      <label htmlFor="booking-rejection-reason">Rejection reason</label>
      <textarea id="booking-rejection-reason" value={reason} disabled={busy} aria-required="true"
        placeholder="e.g. The venue is unavailable due to scheduled maintenance."
        aria-invalid={!!reasonError} aria-describedby={reasonError ? 'booking-rejection-error' : undefined}
        onChange={event => { setReason(event.target.value); setReasonError(undefined) }} rows={4} />
      {reasonError && <p id="booking-rejection-error" className="field-error" role="alert">{reasonError}</p>}
      </div>
      <div className="field">
        <label htmlFor="booking-alternative-venue">Alternative venue (optional)</label>
        <select id="booking-alternative-venue" value={alternativeVenueId} disabled={busy || loadingVenues}
          onChange={event => setAlternativeVenueId(event.target.value)}>
          <option value="">No alternative venue</option>
          {(venues ?? []).filter(venue => venue.venueId !== booking.venue.venueId)
            .map(venue => <option key={venue.venueId} value={venue.venueId}>{venue.venueAddress}</option>)}
        </select>
        {loadingVenues && <p role="status">Loading alternative venues…</p>}
        {venueOptionsError && <div>
          <p role="status">{venueOptionsError}</p>
          <Button type="button" variant="secondary" disabled={busy || loadingVenues} onClick={() => void loadAlternativeVenues()}>
            Retry loading venues
          </Button>
        </div>}
      </div>
      <div className="field">
        <label htmlFor="booking-alternative-arrangement">Alternative arrangement (optional)</label>
        <textarea id="booking-alternative-arrangement" value={alternativeArrangement} disabled={busy}
          maxLength={MAX_ALTERNATIVE_ARRANGEMENT_LENGTH} rows={3}
          onChange={event => setAlternativeArrangement(event.target.value)} />
      </div>
      <div className="booking-approval__actions">
        <Button type="submit" disabled={busy || needsRefresh}>{busy ? 'Rejecting…' : 'Confirm rejection'}</Button>
        <Button type="button" variant="secondary" disabled={busy} onClick={() => {
          setRejecting(false)
          setAlternativeVenueId('')
          setAlternativeArrangement('')
        }}>Cancel</Button>
      </div>
    </BookingDecisionPanel>}
    {error && <div>
      <div role="alert" className="booking-approval__error">
        <p>{error}</p>
        {conflicts.length > 0 && <>
          {unavailablePeriods.length === 0 && <p>The following bookings or tentative holds conflict with this request. All times are Singapore time, UTC+08:00.</p>}
          {conflicts.map(conflict => <BookingConflictCard key={conflict.bookingId} conflict={conflict} request={booking.event} />)}
        </>}
        {unavailablePeriods.length > 0 && <>
          {unavailablePeriods.map(period => <UnavailabilityConflictCard key={period.unavailabilityId} period={period} request={booking.event} />)}
        </>}
      </div>
      <Button variant="secondary" disabled={busy} onClick={() => { void refresh() }}>Refresh booking details</Button>
    </div>}
    {confirming && booking.status === 'pending' && !booking.holds?.some(h => h.status === 'active') && <BookingDecisionPanel aria-labelledby="approve-booking-heading">
      <h3 id="approve-booking-heading">Confirm booking approval</h3>
      <p>Approve this request and commit the venue to this event?</p>
      <dl>
        <dt>Event</dt><dd>{booking.event.eventName}</dd>
        <dt>Venue</dt><dd>{booking.venue.venueAddress}</dd>
        <dt>Starts (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.timing?.startDatetime ?? booking.event.startDatetime)}</dd>
        <dt>Ends (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.timing?.endDatetime ?? booking.event.endDatetime)}</dd>
        {booking.timing && <>
          <dt>Setup / Turnaround</dt><dd>{booking.timing.setupDurationMinutes} / {booking.timing.turnaroundDurationMinutes} minutes</dd>
          <dt>Venue Occupied (SGT)</dt><dd>{formatEventTimeRange(booking.timing.effectiveStart, booking.timing.effectiveEnd)}</dd>
        </>}
      </dl>
      <div className="booking-approval__actions">
        <Button disabled={busy || approvalBlocked} onClick={() => { void decide('approve') }}>{busy ? 'Approving…' : 'Confirm approval'}</Button>
        <Button variant="secondary" disabled={busy} onClick={() => setConfirming(false)}>Cancel</Button>
      </div>
    </BookingDecisionPanel>}
    <div className="booking-details-page__section-header">
      <BookingInformationHeading />
      {booking.status === 'pending' && !confirming && !rejecting && <div className="booking-approval__actions">
        <Button disabled={busy || needsRefresh || approvalBlocked || booking.holds?.some(h => h.status === 'active')} onClick={() => { onDecisionStarted?.(); setConfirming(true) }}>Approve Booking</Button>
        {onHoldRequested && !booking.holds?.some(h => h.status === 'active') && <Button variant="secondary" disabled={busy || needsRefresh}
          onClick={onHoldRequested}>Put on Tentative Hold</Button>}
        <Button variant="secondary" disabled={busy || needsRefresh} onClick={() => {
          onDecisionStarted?.()
          setRejecting(true)
          setReason('')
          setReasonError(undefined)
          setAlternativeVenueId('')
          setAlternativeArrangement('')
          if (venues === null) void loadAlternativeVenues()
        }}>Reject Booking</Button>
      </div>}
    </div>
  </section>
}

export function BookingInformationHeading() {
  return <div className="booking-information-heading">
    <h2 id="venue-information-heading">Venue Information</h2>
  </div>
}
