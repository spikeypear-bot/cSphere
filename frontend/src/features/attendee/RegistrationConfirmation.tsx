import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { registrationDateTime, useRegistrations, type RegistrationDetails } from './registrationView'
import { AttendeeStatusBadges } from './AttendeeStatusBadges'

interface ApprovedEvent {
  venues: { venueId: string; name: string; address: string; unavailable: boolean }[]
}

/** AT03: current owned record plus AT02's approved arrangements, never notification snapshots. */
export function RegistrationConfirmation({ registration, showBadges = true }: { registration: RegistrationDetails; showBadges?: boolean }) {
  const { data: event, error, retry } = useRegistrations<ApprovedEvent>(
    `/attendee/events/${encodeURIComponent(registration.eventId)}`, registration.eventDetailsAvailable)
  return <Card className="attendee-details-card attendee-confirmation">
    <h2>{registration.eventName}</h2>
    {showBadges && <AttendeeStatusBadges value={registration} />}
    <dl className="attendee-event-details__facts">
      <div><dt>Registration reference</dt><dd>{registration.reference}</dd></div>
      <div><dt>Registered</dt><dd>{registrationDateTime(registration.registeredAt)}</dd></div>
      {registration.registrationStatus === 'withdrawn' && <div><dt>Withdrawal date</dt><dd>{registration.withdrawnAt ? registrationDateTime(registration.withdrawnAt) : 'Not recorded'}</dd></div>}
      <div><dt>Starts</dt><dd>{registrationDateTime(registration.startDatetime)}</dd></div>
      <div><dt>Ends</dt><dd>{registrationDateTime(registration.endDatetime)}</dd></div>
      <div><dt>Withdrawal availability</dt><dd>{registration.withdrawalAvailable ? 'Available' : 'Unavailable'}. {registration.withdrawalReason}</dd></div>
    </dl>
    <h3>Approved venues</h3>
    {registration.eventStatus === 'cancelled' ? <p>Venue arrangements are no longer confirmed for attendance.</p>
      : !registration.eventDetailsAvailable ? <p>Approved venue information is unavailable.</p>
        : error ? <><Notice tone="danger">Your registration is saved, but approved venue details could not be loaded.</Notice><Button variant="secondary" onClick={retry}>Retry venue details</Button></>
          : !event ? <p role="status">Loading approved venues…</p>
            : event.venues?.length ? <ul className="attendee-venue-list">{event.venues.map(venue => <li key={venue.venueId}>
              <strong>{venue.name}</strong><span className="attendee-venue-list__address">{venue.address}</span>
              {venue.unavailable && <p className="attendee-venue-list__warning">This venue is unavailable while arrangements are re-confirmed.</p>}
            </li>)}</ul> : <p>No approved venue details are currently available.</p>}
  </Card>
}

/** Render only with an ID returned by a successful registration or an authenticated read. */
export function SavedRegistrationConfirmation({ registrationId, reference, showBadges = true }: { registrationId: string; reference: string; showBadges?: boolean }) {
  const { data, error, retry } = useRegistrations<RegistrationDetails>(`/attendee/registrations/${encodeURIComponent(registrationId)}`)
  return <section aria-label="Registration confirmation" className="attendee-details-card--event">
    {error ? <><Notice tone="info">Registration saved. Reference: {reference}. Confirmation details could not be loaded.</Notice>
      <Button variant="secondary" onClick={retry}>Retry confirmation details</Button></>
      : !data ? <p role="status">Registration saved. Reference: {reference}. Loading confirmation details…</p>
        : <RegistrationConfirmation registration={data} showBadges={showBadges} />}
    <Link className="button button--secondary" to={`/attendee/my-registrations/${encodeURIComponent(registrationId)}`}>View registration confirmation</Link>
  </section>
}
