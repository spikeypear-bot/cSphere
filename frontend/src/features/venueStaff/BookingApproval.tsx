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
        setError(previous => (previous ? previous + ' ' : '') + 'Could not refresh booking details. Refresh before attempting approval again.')
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

  async function approve() {
    if (submitting.current || needsRefresh || booking.status !== 'pending') return
    submitting.current = true
    setBusy(true)
    setError(undefined)
    try {
      const updated = await apiClient.patch<VenueBookingDto>('/venue-bookings/' + booking.bookingId + '/approve')
      if (active.current) {
        setConfirming(false)
        onApproved(updated)
      }
    } catch (cause) {
      if (active.current) {
        setConfirming(false)
        setNeedsRefresh(true)
        const message = cause instanceof ApiClientError ? cause.message : 'Could not confirm the approval result.'
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
      <BookingInformationHeading status={booking.status} />
      {booking.status === 'pending' && !confirming && <Button disabled={busy || needsRefresh} onClick={() => setConfirming(true)}>Approve Booking</Button>}
    </div>
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
        <Button disabled={busy} onClick={() => { void approve() }}>{busy ? 'Approving…' : 'Confirm approval'}</Button>
        <Button variant="secondary" disabled={busy} onClick={() => setConfirming(false)}>Cancel</Button>
      </div>
    </div>}
  </section>
}

/** The status comes directly from the booking returned by the backend. */
export function BookingInformationHeading({ status }: { status: VenueBookingDto['status'] }) {
  return <div className="booking-information-heading">
    <h2 id="venue-information-heading">Venue information</h2>
    <span className="venue-card-preview__tag booking-status-tag" aria-label={'Status: ' + status} aria-live="polite">{status}</span>
  </div>
}
