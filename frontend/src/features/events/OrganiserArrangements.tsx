import { OperatingHours } from '../../components/OperatingHours'
import { useLiveRefresh } from '../../lib/useLiveRefresh'
import { useEffect, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient } from '../../lib/apiClient'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import type { OrganiserArrangementsDto } from '../../types/event'
import { ACCESSIBILITY_LABELS, type AccessibilityFeature } from '../../types/eventRequest'
import { venueFacilityLabels, venueLayoutLabels, type Facility, type VenueLayout } from '../../types/venue'
import '../../components/ui/JourneyStatus.css'
import './EventLifecycle.css'

const accessibilityLabel = (v: string) => ACCESSIBILITY_LABELS[v as AccessibilityFeature] ?? v.replaceAll('_', ' ')
const facilityLabel = (v: string) => venueFacilityLabels[v as Facility] ?? v.replaceAll('_', ' ')
const layoutLabel = (v: string) => venueLayoutLabels[v as VenueLayout] ?? v.replaceAll('_', ' ')

/** Singapore time, whatever the viewer's own timezone (EO04). */
function formatDate(value: string) {
  return formatEventDateTime(value)
}

/**
 * EO04: the confirmed arrangements, as the organiser is allowed to see them.
 * Before confirmation it says so plainly instead of showing half-made plans;
 * after a change it flags what to re-check. Printable as a one-page summary.
 */
export function OrganiserArrangements({ eventId }: { eventId: string }) {
  const liveRevision = useLiveRefresh()
  const [data, setData] = useState<OrganiserArrangementsDto | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    apiClient.get<OrganiserArrangementsDto>(`/events/${eventId}/arrangements`)
      .then((value) => {
        if (cancelled) return
        if (value && typeof value.view === 'string') { setData(value); setFailed(false) }
        else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [eventId, attempt, liveRevision])

  if (failed) {
    return (
      <Card className="arrangements arrangements--pending" role="region" aria-labelledby="arrangements-heading">
        <h2 id="arrangements-heading">Event arrangements</h2>
        <p role="alert">We could not load your event arrangements. Check your connection and try again.</p>
        <Button variant="secondary" onClick={() => { setFailed(false); setAttempt((n) => n + 1) }}>Try again</Button>
      </Card>
    )
  }
  if (!data) return null

  if (data.view === 'not_confirmed' || data.view === 'cancelled') {
    return (
      <Card className="arrangements arrangements--pending" role="region" aria-labelledby="arrangements-heading">
        <h2 id="arrangements-heading">Event arrangements</h2>
        <p>
          {data.view === 'cancelled'
            ? 'This event was cancelled, so there are no arrangements to show.'
            : 'Your arrangements are being finalised. You will be notified as soon as the event is confirmed, and the venue and equipment details will appear here.'}
        </p>
      </Card>
    )
  }

  // Re-confirmed since the first confirmation: when to show "Updated since".
  const updatedSince = data.firstConfirmedAt && data.confirmedAt && data.firstConfirmedAt !== data.confirmedAt
    ? data.firstConfirmedAt : null
  const needs = data.accessibilityNeeds.filter((n) => n !== 'none')

  return (
    <Card className="arrangements" role="region" aria-labelledby="arrangements-heading">
      <div className="arrangements__head">
        <div>
          <h2 id="arrangements-heading">Confirmed arrangements</h2>
          <p className="field-hint">
            {formatDate(data.startDatetime)} – {formatDate(data.endDatetime)} (Singapore time) · {data.expectedAttendance} expected
          </p>
        </div>
        <Button variant="secondary" className="arrangements__print" onClick={() => window.print()}>
          Print summary
        </Button>
      </div>

      {data.view === 'completed' ? (
        <div className="arrangements__alert arrangements__alert--info" role="status">
          <strong>Completed.</strong> This event has taken place; these arrangements are kept as a record.
        </div>
      ) : data.view === 'reconfirming' ? (
        <div className="arrangements__alert" role="status">
          <strong>Arrangements are being updated.</strong> Something changed after confirmation; your coordinator is
          sorting it out and you will be notified once it is confirmed again.
        </div>
      ) : updatedSince ? (
        <div className="arrangements__alert arrangements__alert--info" role="status">
          Updated since first confirmed on {formatDate(updatedSince)}. Please re-check the details below.
        </div>
      ) : null}

      <section className="arrangements__section" aria-label="Venues">
        <h3>{data.venues.length === 1 ? 'Venue' : 'Venues'}</h3>
        {data.venues.length === 0 ? <p className="field-hint">No venue is booked.</p> : null}
        <ul className="arrangements__venues">
          {data.venues.map((venue) => {
            const missing = needs.filter((n) => !venue.accessibilities.includes(n))
            return (
              <li key={venue.venueId} className="arrangements__venue" data-unavailable={venue.noLongerAvailable || undefined}>
                <div className="arrangements__venue-title">
                  <strong>{venue.name}</strong>
                  {venue.requiresAttention && <span className="journey-badge journey-badge--danger journey-badge--small">Requires attention</span>}
                  {venue.noLongerAvailable ? <span className="journey-badge journey-badge--danger journey-badge--small">No longer available</span> : null}
                </div>
                {venue.requiresAttention && <div role="status">
                  <p>This venue arrangement needs to be reassessed by your coordinator and Venue Staff.</p>
                  {venue.suitabilityIssues?.length ? <ul>{venue.suitabilityIssues.map(issue => <li key={issue}>{issue}</li>)}</ul> : null}
                </div>}
                <dl className="arrangements__facts">
                  {venue.capacity ? <div><dt>Capacity</dt><dd>{venue.capacity}</dd></div> : null}
                  {venue.layouts.length > 0 ? <div><dt>Room layout</dt><dd>{venue.layouts.map(layoutLabel).join(', ')}</dd></div> : null}
                  {venue.facilities.length > 0 ? <div><dt>Facilities</dt><dd>{venue.facilities.map(facilityLabel).join(', ')}</dd></div> : null}
                  <div>
                    <dt>Accessibility at this venue</dt>
                    <dd>{venue.accessibilities.length > 0
                      ? venue.accessibilities.map(accessibilityLabel).join(', ')
                      : 'None listed. Ask your coordinator if you need any.'}</dd>
                  </div>
                  <div><dt>Operating hours</dt><dd><OperatingHours hours={venue.operatingHours} /></dd></div>
                </dl>
                {needs.length > 0 ? (
                  <div className="arrangements__access">
                    <span className="arrangements__access-title">Your accessibility needs</span>
                    <ul>
                      {needs.map((n) => (
                        <li key={n} data-met={!missing.includes(n)}>
                          <span aria-hidden="true">{missing.includes(n) ? '!' : '✓'}</span>
                          {accessibilityLabel(n)}
                          <span className="visually-hidden">{missing.includes(n) ? ' — not listed for this venue' : ' — provided'}</span>
                        </li>
                      ))}
                    </ul>
                    {missing.length > 0 ? (
                      <p className="field-hint">Not listed for this venue. Check with your coordinator how it will be handled.</p>
                    ) : null}
                  </div>
                ) : null}
              </li>
            )
          })}
        </ul>
      </section>

      <section className="arrangements__section" aria-label="Equipment">
        <h3>Equipment</h3>
        {data.equipment.length === 0 ? (
          <p className="field-hint">No equipment was arranged for this event.</p>
        ) : (
          <ul className="arrangements__equipment">
            {data.equipment.map((e) => (
              <li key={e.name}><span>{e.name}</span><span className="arrangements__qty">× {e.quantity}</span></li>
            ))}
          </ul>
        )}
      </section>

      <dl className="arrangements__facts arrangements__footer">
        {data.safetyApprovedAt ? <div><dt>Safety check approved</dt><dd>{formatDate(data.safetyApprovedAt)}</dd></div> : null}
        {data.confirmedAt ? <div><dt>Confirmed</dt><dd>{formatDate(data.confirmedAt)}</dd></div> : null}
        <div>
          <dt>Questions?</dt>
          <dd>
            {data.coordinatorEmail ? (
              <a href={`mailto:${data.coordinatorEmail}?subject=${encodeURIComponent(data.eventName)}`}>
                Contact {data.coordinatorName ?? 'your coordinator'}
              </a>
            ) : (data.coordinatorName ?? 'Your Event Coordinator')}
          </dd>
        </div>
      </dl>
    </Card>
  )
}
