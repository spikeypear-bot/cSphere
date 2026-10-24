import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { JourneyStatusBadge, StatusFilter } from '../../components/ui/JourneyStatus'
import type { CoordinatorEventsDto, EventDto } from '../../types/event'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { useVenueRead } from '../venueStaff/useVenueRead'
import './LeadRequestCard.css'
import './ActiveEventsPage.css'

type Grouping = 'coordinator' | 'all'

const countLabel = (count: number) => (count === 1 ? '1 active event' : `${count} active events`)

/** ECL-C4: every active event that has been assigned, across organisations,
 * so the Lead can judge each coordinator's workload before assigning an
 * incoming request. Read-only: each event opens the Lead's oversight view,
 * never a coordinator action. The server decides what is active (Planning
 * through Confirmed, not yet finished) and orders each coordinator's events
 * soonest first. */
export function ActiveEventsPage() {
  // A fresh read on every visit, refresh and window focus (AC8).
  const { data, error, loading, retry } = useVenueRead<CoordinatorEventsDto[]>('/events/by-coordinator', true)
  // A failed reload must not leave an older picture of the assignments on screen.
  const coordinators = error ? undefined : data
  const [grouping, setGrouping] = useState<Grouping>('coordinator')
  const [coordinatorId, setCoordinatorId] = useState('')
  const [status, setStatus] = useState<string | null>(null)

  const total = coordinators?.reduce((sum, coordinator) => sum + coordinator.events.length, 0) ?? 0
  const shown = (coordinators ?? []).filter((c) => coordinatorId === '' || c.coordinatorId === coordinatorId)
  const statuses = shown.flatMap((c) => c.events.flatMap(({ event }) => (event.journeyStatus ? [event.journeyStatus] : [])))
  const matches = (event: EventDto) => status === null || event.journeyStatus?.code === status
  // "All events": one list across coordinators, soonest first.
  const everyEvent = shown
    .flatMap((c) => c.events.map(({ event }) => ({ event, coordinatorName: c.coordinatorName })))
    .filter(({ event }) => matches(event))
    .sort((a, b) => Date.parse(a.event.startDatetime) - Date.parse(b.event.startDatetime))

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator-lead">Event Coordinator Lead console</Link>}
        title="Active events"
        description="Every assigned event from Planning to Confirmed, across organisations, soonest first — to see each Event Coordinator's workload before assigning a new request."
        actions={
          <Button variant="ghost" onClick={retry} disabled={loading} aria-label="Refresh active events">
            <RefreshIcon />{loading && coordinators !== undefined ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {error ? (
        <div role="alert" className="active-events__error">
          <p>Could not load active events. {error}</p>
          <Button variant="secondary" onClick={retry} disabled={loading}>Retry</Button>
        </div>
      ) : null}
      {loading && coordinators === undefined && !error ? <p role="status">Loading active events…</p> : null}

      {coordinators && total === 0 ? (
        <Card className="active-events__empty">
          <p>No active events right now. An event appears here once its request is approved, and leaves once it is cancelled or completed.</p>
        </Card>
      ) : null}
      {coordinators && total > 0 ? (
        <p className="field-hint" role="status">
          {countLabel(total)} across {coordinators.length === 1 ? '1 Event Coordinator' : `${coordinators.length} Event Coordinators`}.
        </p>
      ) : null}

      {coordinators && coordinators.length > 0 ? (
        <div className="active-events__controls">
          <div className="status-filter" role="group" aria-label="Group active events">
            <button type="button" className="chip" aria-pressed={grouping === 'coordinator'}
              onClick={() => setGrouping('coordinator')}>By coordinator</button>
            <button type="button" className="chip" aria-pressed={grouping === 'all'}
              onClick={() => setGrouping('all')}>All events</button>
          </div>
          <div className="field active-events__coordinator">
            <label htmlFor="active-events-coordinator">Event Coordinator</label>
            <select id="active-events-coordinator" value={coordinatorId}
              onChange={(e) => { setCoordinatorId(e.target.value); setStatus(null) }}>
              <option value="">All coordinators</option>
              {coordinators.map((c) => (
                <option key={c.coordinatorId} value={c.coordinatorId}>{c.coordinatorName}</option>
              ))}
            </select>
          </div>
          <StatusFilter statuses={statuses} selected={status} onChange={setStatus} label="Filter active events by status" />
        </div>
      ) : null}

      {coordinators && grouping === 'coordinator' ? shown.map((coordinator) => {
        const events = coordinator.events.filter(({ event }) => matches(event))
        return (
          // Coordinators with none keep their heading and a zero count (AC6).
          <section key={coordinator.coordinatorId} className="active-events__group"
            aria-label={`Active events held by ${coordinator.coordinatorName}`}>
            <h2>
              {coordinator.coordinatorName}{' '}
              <span className="active-events__count">{countLabel(coordinator.events.length)}</span>
            </h2>
            {events.length > 0 ? (
              <ul className="lead-request-cards">
                {events.map(({ event }) => (
                  <li key={event.eventId}><ActiveEventCard event={event} coordinatorName={coordinator.coordinatorName} /></li>
                ))}
              </ul>
            ) : coordinator.events.length > 0 ? (
              <p className="field-hint">None with this status.</p>
            ) : null}
          </section>
        )
      }) : null}

      {coordinators && grouping === 'all' && total > 0 ? (
        everyEvent.length > 0 ? (
          <ul className="lead-request-cards" aria-label="All active events, soonest first">
            {everyEvent.map(({ event, coordinatorName }) => (
              <li key={event.eventId}><ActiveEventCard event={event} coordinatorName={coordinatorName} /></li>
            ))}
          </ul>
        ) : <p className="field-hint">No active events match these filters.</p>
      ) : null}
    </div>
  )
}

/** One active event: what the Lead needs to judge it (AC4) and a read-only
 * way into it (AC7) — no coordinator actions. */
function ActiveEventCard({ event, coordinatorName }: { event: EventDto; coordinatorName: string }) {
  return (
    <Card className="lead-request-card">
      <div className="lead-request-card__head">
        <h3>{event.eventName}</h3>
        {event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : null}
      </div>
      <p className="lead-request-card__organisation">{event.organisation}</p>
      <dl className="lead-request-card__facts">
        <div>
          <dt>When (Singapore time)</dt>
          <dd>{formatEventTimeRange(event.startDatetime, event.endDatetime)}</dd>
        </div>
        <div>
          <dt>Expected attendance</dt>
          <dd>{`${event.expectedAttendance.toLocaleString()} people`}</dd>
        </div>
        <div>
          <dt>Event Coordinator</dt>
          <dd>{event.coordinatorName ?? coordinatorName}</dd>
        </div>
      </dl>
      <Link className="button button--secondary lead-request-card__review" to={`/coordinator-lead/events/${event.eventId}`}
        aria-label={`View event: ${event.eventName}`}>View event</Link>
    </Card>
  )
}
