import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { EVENT_STATUS_LABELS } from '../../types/event'
import { JourneyStatusBadge } from '../../components/ui/JourneyStatus'
import type { SafetyEventListItem } from './readinessTypes'
import { useSafetyData } from './useSafetyData'
import './safetyOfficer.css'

export function SafetyOfficerHomePage() {
  const planning = useSafetyData<SafetyEventListItem[]>('/safety/events/planning')
  const confirmed = useSafetyData<SafetyEventListItem[]>('/safety/events/confirmed')
  // EC08: only events actually sent are the Safety Officer's queue; a
  // withdrawn event (back in Planning) leaves it.
  const waiting = planning.data?.filter((e) => e.status === 'awaiting_safety_review')
  const stillPlanning = planning.data?.filter((e) => e.status !== 'awaiting_safety_review')

  return (
    <div className="page">
      <PageHeader
        title="Safety Officer console"
        description="Review planned events and monitor confirmed event readiness."
      />

      <section className="safety-section" aria-labelledby="safety-review-heading">
        <h2 id="safety-review-heading">Waiting for your safety review</h2>
        <p className="field-hint">Events the Event Coordinator has sent for the Operational Safety Check.</p>
        {planning.error ? <p role="alert">Could not load planning events. {planning.error}</p> : null}
        {planning.loading && !planning.data ? <p role="status">Loading planning events…</p> : null}
        {waiting?.length === 0 ? <p>No events are waiting for your safety review.</p> : null}
        {waiting && waiting.length > 0 ? <EventList events={waiting} /> : null}
      </section>

      <section className="safety-section" aria-labelledby="safety-planning-heading">
        <h2 id="safety-planning-heading">Still being planned</h2>
        <p className="field-hint">Not sent to you yet, sent back for changes, or approved and waiting for the coordinator to confirm. You can start on the checklist early.</p>
        {stillPlanning?.length === 0 ? <p>No other events are being planned.</p> : null}
        {stillPlanning && stillPlanning.length > 0 ? <EventList events={stillPlanning} /> : null}
      </section>

      <section className="safety-section" aria-labelledby="safety-confirmed-heading">
        <h2 id="safety-confirmed-heading">Confirmed events</h2>
        {confirmed.error ? <p role="alert">Could not load confirmed events. {confirmed.error}</p> : null}
        {confirmed.loading && !confirmed.data ? <p role="status">Loading confirmed events…</p> : null}
        {confirmed.data?.length === 0 ? (
          <Card><p>No confirmed events to show.</p></Card>
        ) : null}
        {confirmed.data && confirmed.data.length > 0 ? <EventList events={confirmed.data} /> : null}
      </section>
    </div>
  )
}

function EventList({ events }: { events: SafetyEventListItem[] }) {
  return (
    <ul className="safety-event-list">
      {events.map((event) => (
        <li key={event.eventId}>
          <Card className="safety-event-card">
            <div>
              <h3>{event.eventName}</h3>
              {event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : event.status !== 'confirmed' ? (
                <span className={`status-badge status-badge--${event.status === 'awaiting_safety_review' ? 'pending' : 'approved'}`}
                  data-status={event.status}>
                  {EVENT_STATUS_LABELS[event.status] ?? event.status}
                </span>
              ) : null}
              <dl className="safety-event-facts">
                <div><dt>Date and time</dt><dd>{formatEventTimeRange(event.startDatetime, event.endDatetime)}</dd></div>
                <div><dt>Venue</dt><dd>{event.venueAddress || 'Not finalised'}</dd></div>
              </dl>
            </div>
            <Link className="button button--secondary" to={`/safety-officer/events/${event.eventId}`}>
              View event
            </Link>
          </Card>
        </li>
      ))}
    </ul>
  )
}
