import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { useRegistrations, type RegistrationDetails } from './registrationView'
import { RegistrationConfirmation } from './RegistrationConfirmation'
import { WithdrawRegistrationAction } from './WithdrawRegistrationAction'
import './AttendeePages.css'

export function RegistrationDetailsPage() {
  const { registrationId = '' } = useParams()
  const { data, error, retry, replace, refresh } = useRegistrations<RegistrationDetails>(`/attendee/registrations/${encodeURIComponent(registrationId)}`)
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
      <RegistrationConfirmation registration={data} />
      <WithdrawRegistrationAction key={registrationId} registration={data} onUpdated={replace} onRefresh={refresh} />
      {data.eventDetailsAvailable ? <Link className="button button--secondary attendee-events__open" to={`/attendee/events/${encodeURIComponent(data.eventId)}`}>View latest event details</Link>
        : <Notice tone="info">Attendee-facing event details are currently unavailable.</Notice>}
    </>}
  </main>
}
