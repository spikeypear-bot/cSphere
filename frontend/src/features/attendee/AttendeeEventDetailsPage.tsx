import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient } from '../../lib/apiClient'
import './AttendeePages.css'

interface AttendeeEventDetails {
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
  venues: { venueId: string; name: string; address: string; unavailable: boolean }[]
}

const ACCESSIBILITY_LABELS: Record<string, string> = {
  accessible_parking: 'Accessible parking', drop_off_zone: 'Drop-off zone', public_transport: 'Near public transport',
  step_free_access: 'Step-free access', wide_doorways: 'Wide doorways', elevators: 'Elevators',
  wheelchair_support: 'Wheelchair support', none: 'No specific accessibility provisions',
}

function dateTime(value: string): string {
  const date = new Date(value)
  const offset = value.match(/([+-]\d{2}:\d{2}|Z)$/)?.[0] ?? ''
  const sign = offset.startsWith('-') ? -1 : 1
  const minutes = offset && offset !== 'Z' ? sign * (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4, 6))) : 0
  const wallClock = new Date(date.getTime() + minutes * 60_000)
  const zone = offset === 'Z' ? 'UTC' : `UTC${offset}`
  return `${new Intl.DateTimeFormat('en-SG', { dateStyle: 'long', timeStyle: 'short', timeZone: 'UTC' }).format(wallClock)} (${zone})`
}

const STATUS_LABELS: Record<AttendeeEventDetails['status'], string> = {
  confirmed: 'Confirmed', reconfirming: 'Arrangements being re-confirmed', cancelled: 'Cancelled', completed: 'Past event',
}

export function AttendeeEventDetailsPage() {
  const { eventId = '' } = useParams()
  const [event, setEvent] = useState<AttendeeEventDetails | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [registration, setRegistration] = useState<{ reference: string; registeredAt: string } | null>(null)
  const [registering, setRegistering] = useState(false)
  const [registrationError, setRegistrationError] = useState('')
  const load = useCallback(() => {
    let active = true
    apiClient.get<AttendeeEventDetails>(`/attendee/events/${encodeURIComponent(eventId)}`)
      .then(value => {
        if (active) {
          setEvent(value)
          if (value.registrationReference && value.registeredAt) {
            setRegistration({ reference: value.registrationReference, registeredAt: value.registeredAt })
          }
        }
      })
      .catch(error => {
        if (!active) return
        if (error && typeof error === 'object' && 'status' in error && error.status === 404) setUnavailable(true)
        else setFailed(true)
      })
    return () => { active = false }
  }, [eventId])

  useEffect(() => load(), [load, attempt])

  if (failed) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event details" />
    <Notice tone="danger">Event details could not be loaded. Check your connection and try again.</Notice>
    <Button variant="secondary" onClick={() => { setFailed(false); setUnavailable(false); setAttempt(value => value + 1) }}>Try again</Button>
  </main>
  if (unavailable) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event unavailable" />
    <Notice tone="info">This event is unavailable or does not have attendee-facing details.</Notice>
    <Link to="/attendee">Back to attendee home</Link>
  </main>
  if (!event) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event details" />
    <p role="status">Loading event details…</p>
  </main>

  return <main className="attendee-event-details">
    <PageHeader eyebrow={<Link to="/attendee/browse">Browse events</Link>} title={event.eventName}
      description={<span className="attendee-status" data-status={event.status}>{STATUS_LABELS[event.status]}</span>}
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
        {registration && <Notice tone="success">You’re registered. Reference: <strong>{registration.reference}</strong></Notice>}
        {!registration && event.registrationRequired === true && event.status === 'confirmed' && event.availablePlaces !== null && event.availablePlaces > 0 &&
          <Button disabled={registering} onClick={async () => {
            setRegistering(true); setRegistrationError('')
            try {
              const result = await apiClient.post<{ reference: string; registeredAt: string }>(`/attendee/events/${encodeURIComponent(eventId)}/registrations`)
              setRegistration(result)
              setEvent(current => current ? { ...current,
                availablePlaces: current.availablePlaces === null ? null : Math.max(0, current.availablePlaces - 1),
                registrationReference: result.reference, registeredAt: result.registeredAt,
              } : current)
            } catch (error) {
              const message = error instanceof Error ? error.message : 'Please try again.'
              setRegistrationError(`Registration could not be saved: ${message}`)
            } finally { setRegistering(false) }
          }}>{registering ? 'Registering…' : 'Register for this event'}</Button>}
        {registrationError && <Notice tone="danger">{registrationError} You can retry without creating a duplicate.</Notice>}
        {event.registrationRequired === true && event.availablePlaces === 0 && <p role="status" className="attendee-muted">This event is full.</p>}
      </Card>

      {event.attendeeInstructions && <Card className="attendee-details-card"><h2>Attendee instructions</h2><p>{event.attendeeInstructions}</p></Card>}
    </div>
  </main>
}
