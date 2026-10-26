import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient } from '../../lib/apiClient'
import './AttendeePages.css'

interface AttendeeEventListItem {
  eventId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  status: 'confirmed' | 'reconfirming' | 'cancelled' | 'completed'
  registrationRequired: boolean | null
}

const STATUS_LABELS: Record<AttendeeEventListItem['status'], string> = {
  confirmed: 'Confirmed', reconfirming: 'Being re-confirmed', cancelled: 'Cancelled', completed: 'Past event',
}

function formatDate(value: string): string {
  const date = new Date(value)
  const offset = value.match(/([+-]\d{2}:\d{2}|Z)$/)?.[0] ?? ''
  const sign = offset.startsWith('-') ? -1 : 1
  const minutes = offset && offset !== 'Z' ? sign * (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4, 6))) : 0
  const wallClock = new Date(date.getTime() + minutes * 60_000)
  const timezone = offset === 'Z' ? 'UTC' : `UTC${offset}`
  return `${new Intl.DateTimeFormat('en-SG', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(wallClock)} (${timezone})`
}

export function AttendeeEventsPage() {
  const [events, setEvents] = useState<AttendeeEventListItem[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const load = useCallback(() => {
    let active = true
    apiClient.get<AttendeeEventListItem[]>('/attendee/events')
      .then(value => { if (active) setEvents(value) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [])

  useEffect(() => load(), [load, attempt])

  return <main className="page attendee-events">
    <PageHeader eyebrow="Attendee" title="Browse events" description="Open an event to see its latest attendee-facing details." />
    {failed ? <div className="attendee-state-card">
      <Notice tone="danger">Events could not be loaded. Check your connection and try again.</Notice>
      <Button variant="secondary" onClick={() => { setFailed(false); setAttempt(value => value + 1) }}>Try again</Button>
    </div> : events === null ? <Card className="attendee-state-card" role="status">Loading events…</Card> : events.length === 0 ?
      <Card className="attendee-state-card"><h2>No events yet</h2><p>Confirmed events will appear here when they are ready for attendees.</p></Card> :
      <ul className="attendee-events__list">
        {events.map(event => <li key={event.eventId}>
          <Card className="attendee-events__card">
            <div className="attendee-events__card-heading">
              <h2><Link to={`/attendee/events/${encodeURIComponent(event.eventId)}`}>{event.eventName}</Link></h2>
              <span className="attendee-status" data-status={event.status}>{STATUS_LABELS[event.status]}</span>
            </div>
            <div className="attendee-events__facts">
              <p><span className="attendee-events__fact-label">When</span>{formatDate(event.startDatetime)} – {formatDate(event.endDatetime)}</p>
              <p><span className="attendee-events__fact-label">Registration</span>{event.registrationRequired === true ? 'Required'
              : event.registrationRequired === false ? 'Registration not required' : 'Registration requirement not set'}</p>
            </div>
            <Link className="button button--secondary attendee-events__open" to={`/attendee/events/${encodeURIComponent(event.eventId)}`}>
              View event details<span aria-hidden="true">→</span>
            </Link>
          </Card>
        </li>)}
      </ul>}
  </main>
}
