import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient } from '../../lib/apiClient'

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
  attendeeInstructions: string | null
  venues: { venueId: string; address: string; unavailable: boolean }[]
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
  const load = useCallback(() => {
    let active = true
    apiClient.get<AttendeeEventDetails>(`/attendee/events/${encodeURIComponent(eventId)}`)
      .then(value => { if (active) setEvent(value) })
      .catch(error => {
        if (!active) return
        if (error && typeof error === 'object' && 'status' in error && error.status === 404) setUnavailable(true)
        else setFailed(true)
      })
    return () => { active = false }
  }, [eventId])

  useEffect(() => load(), [load, attempt])

  if (failed) return <main>
    <PageHeader eyebrow="Attendee" title="Event details" />
    <Notice tone="danger">Event details could not be loaded. Check your connection and try again.</Notice>
    <Button variant="secondary" onClick={() => { setFailed(false); setUnavailable(false); setAttempt(value => value + 1) }}>Try again</Button>
  </main>
  if (unavailable) return <main>
    <PageHeader eyebrow="Attendee" title="Event unavailable" />
    <Notice tone="info">This event is unavailable or does not have attendee-facing details.</Notice>
    <Link to="/attendee">Back to attendee home</Link>
  </main>
  if (!event) return <main>
    <PageHeader eyebrow="Attendee" title="Event details" />
    <p role="status">Loading event details…</p>
  </main>

  return <main className="attendee-event-details">
    <PageHeader eyebrow="Attendee event details" title={event.eventName} description={STATUS_LABELS[event.status]} />
    <Link to="/attendee">Back to attendee home</Link>
    {event.status === 'reconfirming' && <Notice tone="info">The event is being re-confirmed. Any venue marked unavailable is affected; other approved venue details remain current.</Notice>}
    {event.status === 'cancelled' && <Notice tone="danger">This event has been cancelled.</Notice>}
    {event.status === 'completed' && <Notice tone="info">This event has finished. These details are shown as a past record.</Notice>}

    <Card>
      <h2>Event information</h2>
      {event.description && <p>{event.description}</p>}
      <dl>
        <dt>Starts</dt><dd>{dateTime(event.startDatetime)}</dd>
        <dt>Ends</dt><dd>{dateTime(event.endDatetime)}</dd>
        <dt>Last confirmed update</dt><dd>{event.updatedAt ? dateTime(event.updatedAt) : 'Not available'}</dd>
        <dt>Accessibility</dt><dd>{event.accessibilityInformation.length
          ? event.accessibilityInformation.map(value => ACCESSIBILITY_LABELS[value] ?? value).join(', ')
          : 'No specific accessibility information recorded'}</dd>
      </dl>
    </Card>

    <Card>
      <h2>Venue</h2>
      {event.venues.length ? <ul>{event.venues.map(venue => <li key={venue.venueId}>
        {venue.address}
        {venue.unavailable && <p role="status">This venue is currently unavailable while arrangements are re-confirmed.</p>}
      </li>)}</ul> : <p>No approved venue details are currently available.</p>}
    </Card>

    <Card>
      <h2>Registration</h2>
      <p>{event.registrationRequired === true ? 'Registration is required.'
        : event.registrationRequired === false ? 'Registration is not required.'
          : 'Registration requirement has not been set.'}</p>
      <p>Availability, capacity and registration dates will appear here when registration settings are available.</p>
      {event.registrationOpensAt && <p>Opens: {dateTime(event.registrationOpensAt)}</p>}
      {event.registrationClosesAt && <p>Closes: {dateTime(event.registrationClosesAt)}</p>}
      {event.registrationCapacity !== null && <p>Capacity: {event.registrationCapacity}</p>}
    </Card>

    {event.attendeeInstructions && <Card><h2>Attendee instructions</h2><p>{event.attendeeInstructions}</p></Card>}
  </main>
}
