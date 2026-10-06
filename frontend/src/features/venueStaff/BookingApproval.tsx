import { useEffect, useRef, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueBookingDto } from '../../types/venueBooking'
import { formatEventDateTime } from './formatEventDateTime'

export function BookingApproval({ booking, onApproved, onUnavailable }: {
  booking: VenueBookingDto
  onApproved: (booking: VenueBookingDto) => void
  onUnavailable: (message: string) => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const submitting = useRef(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])

  async function readCurrentBooking() {
    try {
      const current = await apiClient.get<VenueBookingDto>('/venue-bookings/' + booking.bookingId, { cache: 'no-store' })
      if (active.current) {
        onApproved(current)
        setNeedsRefresh(false)
        setError(previous => previous?.replace(' Checking the latest booking status.', ' Latest booking status: ' + current.status + '.'))
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
  }

  async function refresh() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    setError(undefined)
    await readCurrentBooking()
    submitting.current = false
    if (active.current) setBusy(false)
  }

  async function decide(action: 'approve' | 'reject') {
    if (action === 'reject' && !reason.trim()) { setReasonError('A rejection reason is required.'); return }
    if (submitting.current || needsRefresh || booking.status !== 'pending') return
    submitting.current = true
    setBusy(true)
    setError(undefined)
    try {
      const updated = await apiClient.patch<VenueBookingDto>('/venue-bookings/' + booking.bookingId + '/' + action, action === 'reject' ? { reason: reason.trim() } : undefined)
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
        setError(message + ' Checking the latest booking status.')
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
    <div className="booking-details-page__section-header">
      <BookingInformationHeading />
      {booking.status === 'pending' && !confirming && !rejecting && <div className="booking-approval__actions">
        <Button disabled={busy || needsRefresh} onClick={() => setConfirming(true)}>Approve Booking</Button>
        <Button variant="secondary" disabled={busy || needsRefresh} onClick={() => { setRejecting(true); setReason(''); setReasonError(undefined) }}>Reject Booking</Button>
      </div>}
    </div>
    {rejecting && booking.status === 'pending' && <form className="booking-approval__confirmation" aria-labelledby="reject-booking-heading"
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
      <div className="booking-approval__actions">
        <Button type="submit" disabled={busy || needsRefresh}>{busy ? 'Rejecting…' : 'Confirm rejection'}</Button>
        <Button type="button" variant="secondary" disabled={busy} onClick={() => setRejecting(false)}>Cancel</Button>
      </div>
    </form>}
    {error && <div>
      <p role="alert">{error}</p>
      <Button variant="secondary" disabled={busy} onClick={() => { void refresh() }}>Refresh booking details</Button>
    </div>}
    {confirming && booking.status === 'pending' && <div className="booking-approval__confirmation" aria-labelledby="approve-booking-heading">
      <h3 id="approve-booking-heading">Confirm booking approval</h3>
      <p>Approve this request and commit the venue to this event?</p>
      <dl>
        <dt>Event</dt><dd>{booking.event.eventName}</dd>
        <dt>Venue</dt><dd>{booking.venue.venueAddress}</dd>
        <dt>Starts (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.event.startDatetime)}</dd>
        <dt>Ends (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(booking.event.endDatetime)}</dd>
      </dl>
      <div className="booking-approval__actions">
        <Button disabled={busy} onClick={() => { void decide('approve') }}>{busy ? 'Approving…' : 'Confirm approval'}</Button>
        <Button variant="secondary" disabled={busy} onClick={() => setConfirming(false)}>Cancel</Button>
      </div>
    </div>}
  </section>
}

export function BookingInformationHeading() {
  return <div className="booking-information-heading">
    <h2 id="venue-information-heading">Venue Information</h2>
  </div>
}
