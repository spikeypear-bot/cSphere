import { useState } from 'react'
import { BookingTimingSummary } from '../BookingTimingSummary'
import { OperatingHours } from '../OperatingHours'
import { Button } from '../ui/Button'
import { Modal } from '../ui/Modal'
import type { EventVenueBookingDto } from '../../types/venueBookingRequest'

/** A booking's venue facts (capacity, opening hours, booking timings) behind a
 * "View venue details" button, so booking lists stay short. The window reads
 * the booking it is given, so a live refresh shows in it while open. */
export function BookingVenueDetails({ booking }: { booking: EventVenueBookingDto }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>View venue details</Button>
      {open ? (
        <Modal title={booking.venueAddress ?? 'Venue'} onClose={() => setOpen(false)}>
          <dl className="dash-figures dash-facts">
            <div><dt>Capacity</dt><dd>{booking.venueCapacity == null ? 'Not recorded' : `${booking.venueCapacity.toLocaleString()} people`}</dd></div>
          </dl>
          <dl className="dash-figures dash-facts">
            <div><dt>Operating hours</dt><dd><OperatingHours hours={booking.operatingHours} /></dd></div>
          </dl>
          <BookingTimingSummary timing={booking.timing} />
        </Modal>
      ) : null}
    </>
  )
}
