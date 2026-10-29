import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient } from '../../lib/apiClient'
import { SavedRegistrationConfirmation } from './RegistrationConfirmation'
import { requestNotificationRefresh } from '../notifications/notificationRefresh'
import { AttendeeStatusBadges } from './AttendeeStatusBadges'
import { registrationBadge, type AttendeeStatusInput } from './attendeeStatus'
import { registrationDateTime as dateTime } from './registrationView'
import { useAttendeeResource } from './useAttendeeResource'
import './AttendeePages.css'

interface AttendeeEventDetails extends Omit<AttendeeStatusInput, 'eventStatus'> {
  eventId: string
  eventName: string
  description: string | null
  startDatetime: string
  endDatetime: string
  status: 'confirmed' | 'reconfirming' | 'cancelled' | 'completed'
  updatedAt: string | null
  accessibilityInformation: string[]
  registrationRequired: boolean | null
  registrationOpensAt: string | null
  registrationClosesAt: string | null
  registrationCapacity: number | null
  availablePlaces: number | null
  attendeeInstructions: string | null
  registrationReference: string | null
  registeredAt: string | null
  registrationId?: string | null
  venues: { venueId: string; name: string; address: string; unavailable: boolean }[]
}

const ACCESSIBILITY_LABELS: Record<string, string> = {
  accessible_parking: 'Accessible parking', drop_off_zone: 'Drop-off zone', public_transport: 'Near public transport',
  step_free_access: 'Step-free access', wide_doorways: 'Wide doorways', elevators: 'Elevators',
  wheelchair_support: 'Wheelchair support', none: 'No specific accessibility provisions',
}

export function AttendeeEventDetailsPage() {
  const { eventId = '' } = useParams()
  return <AttendeeEventDetails key={eventId} eventId={eventId} />
}

function AttendeeEventDetails({ eventId }: { eventId: string }) {
  const { data: event, error, retry, replace, refresh } = useAttendeeResource<AttendeeEventDetails>(`/attendee/events/${encodeURIComponent(eventId)}`)
  const [registering, setRegistering] = useState(false)
  const [registrationError, setRegistrationError] = useState('')
  const inFlight = useRef(false)

  if (error && error.status !== 404) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event details" />
    <Notice tone="danger">Event details could not be loaded. Check your connection and try again.</Notice>
    <Button variant="secondary" onClick={retry}>Try again</Button>
  </main>
  if (error?.status === 404) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event unavailable" />
    <Notice tone="info">This event is unavailable or does not have attendee-facing details.</Notice>
    <Link to="/attendee">Back to attendee home</Link>
  </main>
  if (!event) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event details" />
    <p role="status">Loading event details…</p>
  </main>

  const registration = event.registrationReference && event.registeredAt
    ? { reference: event.registrationReference, registeredAt: event.registeredAt, registrationId: event.registrationId ?? undefined } : null

  return <main className="attendee-event-details">
    <PageHeader eyebrow={<Link to="/attendee/browse">Browse events</Link>} title={event.eventName}
      description={<AttendeeStatusBadges value={{ ...event, eventStatus: event.status,
        registrationStatus: event.registrationStatus ?? (event.registrationReference ? 'confirmed' : null) }} />}
      actions={<><Link className="button button--ghost" to="/attendee/my-registrations">My Registrations</Link><Link className="button button--ghost" to="/attendee/browse">All events</Link></>} />
    {event.status === 'reconfirming' && <Notice tone="info">The event is being re-confirmed. Any venue marked unavailable is affected; other approved venue details remain current.</Notice>}
    {event.status === 'cancelled' && <Notice tone="danger">This event has been cancelled.</Notice>}
    {event.status === 'completed' && <Notice tone="info">This event has finished. These details are shown as a past record.</Notice>}

    <div className="attendee-event-details__grid">
      <Card className="attendee-details-card attendee-details-card--event">
        <h2>Event information</h2>
        {event.description && <p className="attendee-details-card__description">{event.description}</p>}
        <dl className="attendee-event-details__facts">
          <div><dt>Starts</dt><dd>{dateTime(event.startDatetime)}</dd></div>
          <div><dt>Ends</dt><dd>{dateTime(event.endDatetime)}</dd></div>
          <div><dt>Last confirmed update</dt><dd>{event.updatedAt ? dateTime(event.updatedAt) : 'Not available'}</dd></div>
        </dl>
      </Card>

      <Card className="attendee-details-card">
        <h2>Venue</h2>
        {event.venues.length ? <ul className="attendee-venue-list">{event.venues.map(venue => <li key={venue.venueId}>
          <strong>{venue.name}</strong>
          <span className="attendee-venue-list__address">{venue.address}</span>
          {venue.unavailable && <p role="status" className="attendee-venue-list__warning">This venue is currently unavailable while arrangements are re-confirmed.</p>}
        </li>)}</ul> : <p className="attendee-muted">No approved venue details are currently available.</p>}
      </Card>

      <Card className="attendee-details-card">
        <h2>Accessibility</h2>
        {event.accessibilityInformation.length ? <ul className="attendee-chip-list">
          {event.accessibilityInformation.map(value => <li className="chip" key={value}>{ACCESSIBILITY_LABELS[value] ?? value}</li>)}
        </ul> : <p className="attendee-muted">No specific accessibility information recorded.</p>}
      </Card>

      <Card className="attendee-details-card">
        <h2>Registration</h2>
        <p className="attendee-registration__requirement">{event.registrationRequired === true ? 'Registration is required.'
          : event.registrationRequired === false ? 'Registration is not required.'
            : 'Registration requirement has not been set.'}</p>
        {event.availablePlaces !== null && <p className="attendee-registration__availability">{event.availablePlaces} {event.availablePlaces === 1 ? 'place' : 'places'} available</p>}
        {(event.registrationOpensAt || event.registrationClosesAt || event.registrationCapacity !== null) && <dl className="attendee-event-details__facts">
          {event.registrationOpensAt && <div><dt>Opens</dt><dd>{dateTime(event.registrationOpensAt)}</dd></div>}
          {event.registrationClosesAt && <div><dt>Closes</dt><dd>{dateTime(event.registrationClosesAt)}</dd></div>}
          {event.registrationCapacity !== null && <div><dt>Capacity</dt><dd>{event.registrationCapacity}</dd></div>}
        </dl>}
        {registration && !registration.registrationId && <p>Registration reference: <strong>{registration.reference}</strong></p>}
        {!registration && registrationBadge({ ...event, eventStatus: event.status, registrationStatus: null }).label === 'Open for Registration' &&
          <Button disabled={registering} onClick={async () => {
            if (inFlight.current) return
            inFlight.current = true
            setRegistering(true); setRegistrationError('')
            try {
              const result = await apiClient.post<{ registrationId?: string; reference: string; registeredAt: string; status?: 'confirmed' | 'event_cancelled' }>(`/attendee/events/${encodeURIComponent(eventId)}/registrations`)
              if (!replace(current => ({ ...(current ?? event), registrationStatus: result.status ?? 'confirmed',
                status: result.status === 'event_cancelled' ? 'cancelled' : (current ?? event).status,
                registrationReference: result.reference, registeredAt: result.registeredAt, registrationId: result.registrationId }))) return
              requestNotificationRefresh()
              // An idempotent response need not consume another place. Refresh authoritative capacity.
              if (result.registrationId) {
                void refresh().catch(() => { /* Saved confirmation remains accessible. */ })
              }
            } catch (error) {
              let recovered = false
              try {
                const current = await refresh()
                recovered = Boolean(current?.registrationReference && current.registeredAt)
                if (recovered) requestNotificationRefresh()
              } catch { /* A safe idempotent retry remains available if status cannot be read. */ }
              if (!recovered) {
                const message = error instanceof Error ? error.message : 'Please try again.'
                setRegistrationError(`Registration could not be saved: ${message}`)
              }
            } finally { inFlight.current = false; setRegistering(false) }
          }}>{registering ? 'Registering…' : 'Register for this event'}</Button>}
        {registrationError && <Notice tone="danger">{registrationError} You can retry without creating a duplicate.</Notice>}
      </Card>

      {registration?.registrationId && <SavedRegistrationConfirmation registrationId={registration.registrationId} reference={registration.reference} showBadges={false} />}
      {event.attendeeInstructions && <Card className="attendee-details-card"><h2>Attendee instructions</h2><p>{event.attendeeInstructions}</p></Card>}
    </div>
  </main>
}
