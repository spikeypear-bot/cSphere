import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { registrationDateTime, useRegistrations, type RegistrationDetails } from './registrationView'
import { AttendeeStatusBadges } from './AttendeeStatusBadges'
import './AttendeePages.css'

export function MyRegistrationsPage() {
  const { data, error, retry } = useRegistrations<RegistrationDetails[]>('/attendee/registrations')
  return <main className="page attendee-event-details">
    <PageHeader eyebrow="Attendee" title="My Registrations" description="Your registrations and history, with the latest event status."
      actions={<Link className="button button--secondary" to="/attendee/browse">Browse events</Link>} />
    {error ? <Card className="attendee-state-card">
      <Notice tone="danger">{error.status === 403 ? 'Only attendees can view their registrations.'
        : 'Registrations could not be loaded. Check your connection and try again.'}</Notice>
      <Button variant="secondary" onClick={retry}>Try again</Button>
    </Card> : !data ? <p role="status">Loading registrations…</p> : data.length === 0 ?
      <Card className="attendee-state-card"><h2>No registrations yet</h2><p>Register for an event to see your status here.</p></Card> :
      <ul className="attendee-events__list">
        {data.map(registration => <li key={registration.registrationId}>
          <Card className="attendee-events__card">
            <h2><Link to={`/attendee/my-registrations/${encodeURIComponent(registration.registrationId)}`}>{registration.eventName}</Link></h2>
            <AttendeeStatusBadges value={registration} />
            <dl className="attendee-event-details__facts">
              <div><dt>Registration reference</dt><dd>{registration.reference}</dd></div>
              <div><dt>Starts</dt><dd>{registrationDateTime(registration.startDatetime)}</dd></div>
              <div><dt>Ends</dt><dd>{registrationDateTime(registration.endDatetime)}</dd></div>
              <div><dt>Registered</dt><dd>{registrationDateTime(registration.registeredAt)}</dd></div>
            </dl>
            <Link className="button button--secondary attendee-events__open" to={`/attendee/my-registrations/${encodeURIComponent(registration.registrationId)}`}>View registration</Link>
          </Card>
        </li>)}
      </ul>}
  </main>
}
