import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { CoordinatorVenueOperationalIssueDto } from '../../types/venueOperationalIssue'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './OperationalIssuesPage.css'

function periodLabel(issue: CoordinatorVenueOperationalIssueDto) {
  if (!issue.affectedFrom || !issue.affectedUntil) return 'Period not specified'
  return `${formatEventDateTime(issue.affectedFrom)} – ${formatEventDateTime(issue.affectedUntil)}`
}

export function OperationalIssuesPage() {
  const [issues, setIssues] = useState<CoordinatorVenueOperationalIssueDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setIssues(await apiClient.get<CoordinatorVenueOperationalIssueDto[]>('/coordinator/operational-issues',
        { cache: 'no-store' }))
      setError(null)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not load venue operational issues.')
    }
  }, [])

  useEffect(() => {
    let active = true
    apiClient.get<CoordinatorVenueOperationalIssueDto[]>('/coordinator/operational-issues',
      { cache: 'no-store' })
      .then(data => {
        if (active) {
          setIssues(data)
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (active) {
          setError(err instanceof ApiClientError ? err.message : 'Could not load venue operational issues.')
        }
      })
    return () => { active = false }
  }, [])

  return (
    <div className="coordinator-home operational-issues-page">
      <div className="coordinator-home__header">
        <div>
          <h1>Venue operational issues</h1>
          <p className="field-hint">
            Issues affecting venues with your confirmed event arrangements.
          </p>
        </div>
        <Button variant="secondary" onClick={() => void load()}>Refresh issues</Button>
      </div>

      {error ? <p role="alert">{error}</p> : null}
      {issues === null && !error ? <p role="status">Loading operational issues…</p> : null}
      {issues?.length === 0 ? (
        <Card className="operational-issues-page__empty">
          <p>No operational issues affect your confirmed venue arrangements.</p>
        </Card>
      ) : null}
      {issues && issues.length > 0 ? (
        <ul className="review-queue__list">
          {issues.map(issue => (
            <li key={issue.issueId}>
              <Card className="review-queue__row">
                <h2>{issue.venueAddress}</h2>
                <p>{issue.description}</p>
                <p><strong>Affected period:</strong> {periodLabel(issue)}</p>
                {issue.overlappingEvents.length > 0 ? (
                  <div>
                    <h3>Overlapping managed events</h3>
                    <ul>
                      {issue.overlappingEvents.map(event => (
                        <li key={event.eventId}>
                          <Link to={`/coordinator/events/${event.eventId}`}>{event.eventName}</Link>
                          {' '}({formatEventDateTime(event.startDatetime)} – {formatEventDateTime(event.endDatetime)})
                        </li>
                      ))}
                    </ul>
                    <p className="field-hint">
                      Existing bookings remain unchanged. Review and adjust arrangements separately.
                    </p>
                  </div>
                ) : (
                  <p className="field-hint">No confirmed managed event overlaps this period.</p>
                )}
                <p className="field-hint">Reported {formatEventDateTime(issue.createdAt)}</p>
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
