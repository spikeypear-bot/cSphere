import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { VenueDetailsPanel } from '../venueStaff/VenueDetailsPanel'
import { useVenueRead } from '../venueStaff/useVenueRead'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { BOOKING_STATUS_LABELS, type VenueBookingDisplayStatus } from '../../types/venueBookingStatus'
import type { VenueDto } from '../../types/venue'
import { hasCoordinates, venueName, type VenueMapBooking } from './mapData'

function BookingRow({ booking, refresh }: { booking: VenueMapBooking; refresh: () => void }) {
  const [expiredHold, setExpiredHold] = useState(false)
  const refreshRef = useRef(refresh)
  useEffect(() => { refreshRef.current = refresh }, [refresh])
  useEffect(() => {
    if (!booking.holdExpiresAt) return
    const deadline = Date.now() + Math.max(0, Date.parse(booking.holdExpiresAt) - Date.parse(booking.serverTime))
    let timer: ReturnType<typeof setTimeout>
    const check = () => {
      const remaining = deadline - Date.now()
      if (remaining > 0) timer = setTimeout(check, Math.min(remaining, 2147483647))
      else { setExpiredHold(true); refreshRef.current() }
    }
    check()
    return () => clearTimeout(timer)
  }, [booking.holdExpiresAt, booking.serverTime])
  const status: VenueBookingDisplayStatus = booking.status === 'pending' && booking.holdExpiresAt && !expiredHold ? 'tentative' : booking.status
  return <li><Card className="feature-skeleton__body venue-map-booking">
    <strong>{booking.eventName}</strong>
    <span className="booking-status-tag" data-booking-status={status}>{BOOKING_STATUS_LABELS[status]}</span>
    <p>{formatEventTimeRange(booking.startDatetime, booking.endDatetime)} (SGT)</p>
    <Link to={`/venue-staff/bookings/${booking.bookingId}`}>View booking details</Link>
  </Card></li>
}

export function VenueMapDetails({ venue, date, onClose }: { venue: VenueDto; date: string; onClose: () => void }) {
  const [includePast, setIncludePast] = useState(false)
  const params = new URLSearchParams({ includePast: String(includePast) })
  if (date) params.set('date', date)
  const bookings = useVenueRead<VenueMapBooking[]>(`/venue-staff/map/venues/${venue.venueId}/bookings?${params}`, false, 30000, true, false)
  const current = bookings.data?.filter(b => !b.past) ?? []
  const past = bookings.data?.filter(b => b.past) ?? []
  return <section className="venue-map-details" aria-label="Selected venue details">
    <VenueDetailsPanel venue={venue} readOnly title={venueName(venue)} headingLevel={3}
      actions={<><Link className="button button--secondary" to={`/venue-staff/catalogue/${venue.venueId}`}>View venue details</Link><Button variant="ghost" onClick={onClose}>Close details</Button></>} />
    {!hasCoordinates(venue) && <p className="field-hint">Map location unavailable. Venue details and bookings are still available.</p>}
    <div className="venue-map-heading"><h3>Events at this venue</h3><Button className="venue-refresh-button" variant="ghost" onClick={bookings.retry}>Refresh bookings</Button></div>
    {!date && <label className="venue-map-check"><input type="checkbox" checked={includePast}
      onChange={e => setIncludePast(e.target.checked)} />Show past approved bookings</label>}
    <p className="field-hint">All times are Singapore time. An approved venue booking may belong to an event that is still in planning.</p>
    {bookings.error ? <div role="alert"><p>{bookings.error}</p><Button variant="secondary" onClick={bookings.retry}>Retry bookings</Button></div>
      : !bookings.data ? <p role="status">Loading bookings…</p>
      : <>
        {current.length === 0 && past.length === 0 && <Card className="feature-skeleton__body"><p>{date ? 'No bookings on this date.' : 'No upcoming bookings for this venue.'}</p></Card>}
        {current.length > 0 && <ul className="venue-map-bookings">{current.map(b => <BookingRow key={`${b.bookingId}:${b.holdExpiresAt}`} booking={b} refresh={bookings.retry} />)}</ul>}
        {past.length > 0 && <><h4>Past approved bookings</h4><ul className="venue-map-bookings">{past.map(b => <BookingRow key={b.bookingId} booking={b} refresh={bookings.retry} />)}</ul></>}
      </>}
  </section>
}
