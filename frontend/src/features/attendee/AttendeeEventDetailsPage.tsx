import { useCallback, useEffect, useRef, useState } from 'react'
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

const mapLink = (address: string) => `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`

export function AttendeeEventDetailsPage() {
  const { eventId = '' } = useParams()
  return <AttendeeEventDetails key={eventId} eventId={eventId} />
}

function AttendeeEventDetails({ eventId }: { eventId: string }) {
  const { data: event, error, retry, replace, refresh } = useAttendeeResource<AttendeeEventDetails>(`/attendee/events/${encodeURIComponent(eventId)}`)
  const failed = Boolean(error && error.status !== 404)
  const unavailable = error?.status === 404
  const registration = event?.registrationReference && event.registeredAt
    ? { reference: event.registrationReference, registeredAt: event.registeredAt, registrationId: event.registrationId ?? undefined }
    : null
  const [registering, setRegistering] = useState(false)
  const [registrationError, setRegistrationError] = useState('')
  const [justRegistered, setJustRegistered] = useState(false)
  const confirmationRef = useRef<HTMLDivElement>(null)
  

  // After a successful registration, bring the confirmation into view and announce it.
  useEffect(() => {
    if (!justRegistered || !registration?.registrationId) return
    const node = confirmationRef.current
    if (!node) return
    node.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
    node.focus?.({ preventScroll: true })
  }, [justRegistered, registration?.registrationId])

  const refreshEvent = useCallback(() => { void refresh().catch(() => { /* keep current view; user can retry */ }) }, [refresh])

  async function handleRegister() {
    setRegistering(true); setRegistrationError('')
    try {
      const result = await apiClient.post<{ registrationId: string; reference: string; registeredAt: string }>(`/attendee/events/${encodeURIComponent(eventId)}/registrations`)
      replace(current => ({ ...current!, registrationStatus: 'confirmed', registrationReference: result.reference,
        registeredAt: result.registeredAt, registrationId: result.registrationId }))
      setJustRegistered(true)
      requestNotificationRefresh()
      // An idempotent response need not consume another place. Refresh authoritative capacity.
      if (result.registrationId) refreshEvent()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Please try again.'
      const isConflict = error && typeof error === 'object' && 'status' in error && error.status === 409
      const isNetworkFailure = error && typeof error === 'object' && 'status' in error && error.status === 0
      const latest = (isConflict || isNetworkFailure) ? await refresh().catch(() => undefined) : undefined
      if (latest?.registrationReference && latest.registeredAt) {
        setJustRegistered(true)
        setRegistrationError('')
        return
      }
      setRegistrationError(`Registration could not be saved: ${message}`)
      // AT07: refresh so a registration that filled up meanwhile shows the latest "Full" status.
      if (isConflict) {
        const isNowFull = latest?.availablePlaces === 0
        if (isNowFull) setRegistrationError('')
      }
    } finally { setRegistering(false) }
  }

  if (failed && !event) return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="Event details" />
    <Notice tone="danger">Event details could not be loaded. Check your connection and try again.</Notice>
    <Button variant="secondary" onClick={retry}>Try again</Button>
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

  const badgeLabel = registrationBadge({ ...event, eventStatus: event.status, registrationStatus: null }).label
  const availabilityNotice = registrationAvailabilityNotice(event, failed, badgeLabel)
  const canRegister = !registration && !failed && badgeLabel === 'Open for Registration'
  const showRegistrationDetails = event.status !== 'cancelled' && event.status !== 'completed'

  const capacity = event.registrationCapacity
  const places = event.availablePlaces
  const hasMeter = showRegistrationDetails && capacity !== null && capacity > 0 && places !== null
  const takenPercent = hasMeter ? Math.min(100, Math.max(0, ((capacity - places) / capacity) * 100)) : 0
  const lowPlaces = hasMeter && places > 0 && places <= Math.max(3, Math.ceil(capacity * 0.2))

  return <main className="page attendee-event-details">
    <PageHeader eyebrow={<Link to="/attendee/browse">Browse events</Link>} title={event.eventName}
      description={<AttendeeStatusBadges value={{ ...event, eventStatus: event.status,
        registrationStatus: event.registrationStatus ?? (event.registrationReference ? 'confirmed' : null) }} />}
      actions={<Link className="button button--ghost" to="/attendee/my-registrations">My Registrations</Link>} />
    {failed && <Notice tone="danger">The latest registration availability could not be retrieved, so registration is not shown as available.{' '}
      <Button variant="secondary" onClick={retry}>Try again</Button></Notice>}
    {event.status === 'reconfirming' && <Notice tone="info">The event is being re-confirmed. Any venue marked unavailable is affected; other approved venue details remain current.</Notice>}
    {event.status === 'cancelled' && <Notice tone="danger">This event has been cancelled.</Notice>}
    {event.status === 'completed' && <Notice tone="info">This event has finished. These details are shown as a past record.</Notice>}

    <div className="attendee-event-details__layout">
      <div className="attendee-event-details__main">
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
          {event.venues.length ? <ul className="attendee-venue-list">{event.venues.map(venue => <li key={venue.venueId}
            className={venue.unavailable ? 'attendee-venue-list__item attendee-venue-list__item--unavailable' : 'attendee-venue-list__item'}>
            <strong>{venue.name}</strong>
            <span className="attendee-venue-list__address">{venue.address}</span>
            {!venue.unavailable && venue.address && <a className="attendee-venue-list__map" href={mapLink(venue.address)}
              target="_blank" rel="noopener noreferrer">View on map<span className="attendee-visually-hidden"> (opens in a new tab)</span></a>}
            {venue.unavailable && <p role="status" className="attendee-venue-list__warning">This venue is currently unavailable while arrangements are re-confirmed.</p>}
          </li>)}</ul> : <p className="attendee-muted">No approved venue details are currently available.</p>}
        </Card>

        <Card className="attendee-details-card">
          <h2>Accessibility</h2>
          {event.accessibilityInformation.length ? <ul className="attendee-chip-list">
            {event.accessibilityInformation.map(value => <li className="chip attendee-chip--feature" key={value}>{ACCESSIBILITY_LABELS[value] ?? value}</li>)}
          </ul> : <p className="attendee-muted">No specific accessibility information recorded.</p>}
        </Card>

        {event.attendeeInstructions && <Card className="attendee-details-card"><h2>Attendee instructions</h2><p>{event.attendeeInstructions}</p></Card>}
      </div>

      <div className="attendee-event-details__aside">
        <Card className="attendee-details-card attendee-details-card--registration">
          <h2>Registration</h2>
          <p className="attendee-registration__requirement">{event.registrationRequired === true ? 'Registration is required.'
            : event.registrationRequired === false ? 'Registration is not required.'
              : 'Registration requirement has not been set.'}</p>
          {showRegistrationDetails && places !== null && <p className={`attendee-registration__availability${lowPlaces ? ' attendee-registration__availability--low' : ''}`}>
            {places} {places === 1 ? 'place' : 'places'} available</p>}
          {hasMeter && <div className="attendee-meter" aria-hidden="true">
            <div className={`attendee-meter__fill${lowPlaces ? ' attendee-meter__fill--low' : ''}`} style={{ width: `${takenPercent}%` }} />
          </div>}
          {showRegistrationDetails && (event.registrationOpensAt || event.registrationClosesAt || capacity !== null) && <dl className="attendee-event-details__facts">
            {event.registrationOpensAt && <div><dt>Opens</dt><dd>{dateTime(event.registrationOpensAt)}</dd></div>}
            {event.registrationClosesAt && <div><dt>Closes</dt><dd>{dateTime(event.registrationClosesAt)}</dd></div>}
            {capacity !== null && <div><dt>Capacity</dt><dd>{capacity}</dd></div>}
          </dl>}
          {registration && !registration.registrationId && <p>Registration reference: <strong>{registration.reference}</strong></p>}
          {!registration && availabilityNotice && <Notice tone={availabilityNotice.tone}>{availabilityNotice.text}</Notice>}
          {canRegister && <div className="attendee-registration__action">
            <Button disabled={registering} aria-busy={registering} onClick={handleRegister}>{registering ? 'Registering…' : 'Register for this event'}</Button>
          </div>}
          {registrationError && <Notice tone="danger">{registrationError} You can retry without creating a duplicate.</Notice>}
        </Card>

        {registration?.registrationId && <div ref={confirmationRef} tabIndex={-1} className="attendee-event-details__confirmation">
          <SavedRegistrationConfirmation registrationId={registration.registrationId} reference={registration.reference} showBadges={false} />
        </div>}
      </div>
    </div>
  </main>
}

function registrationAvailabilityNotice(event: AttendeeEventDetails, failed: boolean, label: string):
  { tone: 'danger' | 'info'; text: string } | null {
  if (failed || event.registrationRequired !== true) return null
  switch (label) {
    case 'Full': return { tone: 'danger', text: 'Registration is full. This event is full. No places are available.' }
    case 'Not Yet Open': return { tone: 'info', text: `Registration has not opened yet${event.registrationOpensAt ? ` and opens ${dateTime(event.registrationOpensAt)}` : ''}.` }
    case 'Registration Closed': return { tone: 'info', text: 'Registration has closed.' }
    case 'Registration Paused': return { tone: 'info', text: 'Registration is paused while the event is re-confirmed.' }
    case 'Registration Unavailable': return event.status === 'cancelled' ? null
      : { tone: 'info', text: 'Registration is currently unavailable for this event.' }
    default: return null
  }
}
