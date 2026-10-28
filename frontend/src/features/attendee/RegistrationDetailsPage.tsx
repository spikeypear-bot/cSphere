import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { EVENT_LABELS, REGISTRATION_LABELS, registrationDateTime, useRegistrations, type RegistrationDetails } from './registrationView'
import './AttendeePages.css'

export function RegistrationDetailsPage() {
  const { registrationId = '' } = useParams()
  const { data, error, retry } = useRegistrations<RegistrationDetails>(`/attendee/registrations/${encodeURIComponent(registrationId)}`)
  return <main className="page attendee-event-details">
    <PageHeader eyebrow={<Link to="/attendee/my-registrations">My Registrations</Link>} title="Registration details" />
    {error ? <Card className="attendee-state-card">
      <Notice tone="danger">{error.status === 404 ? 'This registration is unavailable or does not belong to your account.'
        : error.status === 403 ? 'Only attendees can view their registrations.'
          : 'Registration details could not be loaded. Check your connection and try again.'}</Notice>
      {error.status !== 404 && <Button variant="secondary" onClick={retry}>Try again</Button>}
    </Card> : !data ? <p role="status">Loading registration details…</p> : <>
      {data.eventStatus === 'cancelled' && <Notice tone="danger">This event has been cancelled. Event cancellation is separate from withdrawing your registration.</Notice>}
      {data.eventStatus === 'reconfirming' && <Notice tone="info">The event is awaiting re-confirmation. Existing registrations have not been automatically withdrawn.</Notice>}
      {data.eventStatus === 'completed' && <Notice tone="info">This event has completed. Your registration remains visible for history.</Notice>}
      <Card className="attendee-details-card">
        <h2>{data.eventName}</h2>
        <dl className="attendee-event-details__facts">
          <div><dt>Registration status</dt><dd><span className="attendee-status" data-status={data.registrationStatus}>{REGISTRATION_LABELS[data.registrationStatus]}</span></dd></div>
          <div><dt>Event status</dt><dd><span className="attendee-status" data-status={data.eventStatus}>{EVENT_LABELS[data.eventStatus]}</span></dd></div>
          <div><dt>Registration reference</dt><dd>{data.reference}</dd></div>
          <div><dt>Registered</dt><dd>{registrationDateTime(data.registeredAt)}</dd></div>
          <div><dt>Starts</dt><dd>{registrationDateTime(data.startDatetime)}</dd></div>
          <div><dt>Ends</dt><dd>{registrationDateTime(data.endDatetime)}</dd></div>
          <div><dt>Withdrawal availability</dt><dd>{data.withdrawalAvailable ? 'Available' : 'Unavailable'}. {data.withdrawalReason}</dd></div>
        </dl>
      </Card>
      {data.eventDetailsAvailable ? <Link className="button button--secondary attendee-events__open" to={`/attendee/events/${encodeURIComponent(data.eventId)}`}>View latest event details</Link>
        : <Notice tone="info">Attendee-facing event details are currently unavailable.</Notice>}
    </>}
  </main>
}
