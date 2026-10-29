import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { useAttendeeResource } from './useAttendeeResource'
import { AttendeeStatusBadges } from './AttendeeStatusBadges'
import { registrationDateTime } from './registrationView'
import type { AttendeeStatusInput } from './attendeeStatus'
import './AttendeePages.css'

interface AttendeeEventListItem extends Omit<AttendeeStatusInput, 'eventStatus'> {
  eventId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  status: 'confirmed' | 'reconfirming' | 'cancelled' | 'completed'
  registrationRequired: boolean | null
}

export function AttendeeEventsPage() {
  const { data: events, error, retry } = useAttendeeResource<AttendeeEventListItem[]>('/attendee/events')

  return <main className="page attendee-events">
    <PageHeader eyebrow="Attendee" title="Browse events" description="Open an event to see its latest attendee-facing details." />
    {error ? <div className="attendee-state-card">
      <Notice tone="danger">Events could not be loaded. Check your connection and try again.</Notice>
      <Button variant="secondary" onClick={retry}>Try again</Button>
    </div> : !events ? <Card className="attendee-state-card" role="status">Loading events…</Card> : events.length === 0 ?
      <Card className="attendee-state-card"><h2>No events yet</h2><p>Confirmed events will appear here when they are ready for attendees.</p></Card> :
      <ul className="attendee-events__list">
        {events.map(event => <li key={event.eventId}>
          <Card className="attendee-events__card">
            <div className="attendee-events__card-heading">
              <h2><Link to={`/attendee/events/${encodeURIComponent(event.eventId)}`}>{event.eventName}</Link></h2>
            </div>
            <AttendeeStatusBadges value={{ ...event, eventStatus: event.status }} />
            <div className="attendee-events__facts">
              <p><span className="attendee-events__fact-label">When</span>{registrationDateTime(event.startDatetime)} – {registrationDateTime(event.endDatetime)}</p>
            </div>
            <Link className="button button--secondary attendee-events__open" to={`/attendee/events/${encodeURIComponent(event.eventId)}`}>
              View event details<span aria-hidden="true">→</span>
            </Link>
          </Card>
        </li>)}
      </ul>}
  </main>
}
