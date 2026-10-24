import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { JourneyStatusBadge, StatusFilter } from '../../components/ui/JourneyStatus'
import { EVENT_STATUS_LABELS, type EventDto } from '../../types/event'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { useVenueRead } from '../venueStaff/useVenueRead'
import type { ChangeRequestView } from '../../types/changeRequest'
import './AssignedEventsPage.css'

/** EC09: the signed-in coordinator's active events, in the server's order (soonest first). */
export function AssignedEventsPage() {
  // Shared GET-on-mount hook; keeps the cards on screen during a refresh.
  const { data: events, error, loading, retry } = useVenueRead<EventDto[]>('/events/assigned', true)
  const refreshing = loading && events !== undefined
  // EC-NEW3 AC4: pending change requests on this coordinator's events.
  const { data: changes } = useVenueRead<ChangeRequestView[]>('/change-requests', true)
  const changeByEvent = new Map((changes ?? []).filter((c) => c.eventId).map((c) => [c.eventId!, c]))
  // EC-NEW2: filter by the same status everyone sees.
  const [filter, setFilter] = useState<string | null>(null)
  const statuses = (events ?? []).flatMap((e) => (e.journeyStatus ? [e.journeyStatus] : []))
  const shown = (events ?? []).filter((e) => filter === null || e.journeyStatus?.code === filter)

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator">Event Coordinator console</Link>}
        title="My events"
        description="Events assigned to you that are being planned or are confirmed, soonest first."
        actions={
          <Button variant="ghost" onClick={retry} disabled={loading} aria-label="Refresh my events">
            <RefreshIcon />{refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {error ? <p role="alert">Could not load your events. {error}</p> : null}
      {loading && events === undefined ? <p role="status">Loading your events…</p> : null}
      {events && events.length === 0 ? (
        <Card className="assigned-events__empty">
          <p>No events are assigned to you right now. An event appears here once a request assigned to you is approved.</p>
        </Card>
      ) : null}

      {events && events.length > 0 ? (
        <>
          <p className="field-hint" role="status">
            {events.length === 1 ? '1 event is' : `${events.length} events are`} assigned to you.
          </p>
          {changeByEvent.size > 0 ? (
            <p className="field-hint" role="status">
              {changeByEvent.size === 1 ? '1 change request is' : `${changeByEvent.size} change requests are`} waiting for your decision.
            </p>
          ) : null}
          <StatusFilter statuses={statuses} selected={filter} onChange={setFilter} label="Filter my events by status" />
          <ul className="assigned-events__grid">
            {shown.map((event) => (
              <li key={event.eventId}>
                <AssignedEventCard event={event} change={changeByEvent.get(event.eventId)} />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  )
}

function AssignedEventCard({ event, change }: { event: EventDto; change?: ChangeRequestView }) {
  return (
    <Card className="assigned-events__card">
      <div className="assigned-events__card-head">
        <h2>{event.eventName}</h2>
        {event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : (
          <span className={`status-badge status-badge--${event.status === 'pending' ? 'pending' : 'approved'}`}>
            {EVENT_STATUS_LABELS[event.status] ?? event.status}
          </span>
        )}
      </div>
      <p className="assigned-events__organisation">{event.organisation}</p>
      {change ? (
        <Link className="change-request__status change-request__status--pending"
          to={`/coordinator/change-requests/${change.changeRequestId}`}>Change requested · review</Link>
      ) : null}
      <dl className="assigned-events__facts">
        <div>
          <dt>When (Singapore time)</dt>
          <dd>{formatEventTimeRange(event.startDatetime, event.endDatetime)}</dd>
        </div>
        <div>
          <dt>Expected attendance</dt>
          <dd>{event.expectedAttendance.toLocaleString()} people</dd>
        </div>
      </dl>
      <Link
        to={`/coordinator/events/${event.eventId}`}
        className="button button--secondary assigned-events__open"
        aria-label={`Open event ${event.eventName}`}
      >
        Open event
      </Link>
      <Link
        to={`/coordinator/events/${event.eventId}/reassignment-request`}
        className="button button--secondary assigned-events__open"
      >
        Request reassignment
      </Link>
    </Card>
  )
}
