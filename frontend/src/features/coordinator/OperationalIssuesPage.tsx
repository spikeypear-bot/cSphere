import { useCallback, useEffect, useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { RowLink } from '../../components/ui/RowLink'
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
    <div className="page">
      <PageHeader
        title="Venue operational issues"
        description="Issues affecting venues with your approved bookings."
        actions={<Button variant="secondary" onClick={() => void load()}>Refresh issues</Button>}
      />

      {error ? <p role="alert">{error}</p> : null}
      {issues === null && !error ? <p role="status">Loading operational issues…</p> : null}
      {issues?.length === 0 ? (
        <Card className="operational-issue">
          <p>No operational issues affect your approved venue bookings.</p>
        </Card>
      ) : null}
      {issues && issues.length > 0 ? (
        <ul className="plain-list">
          {issues.map(issue => (
            <li key={issue.issueId}>
              <Card className="dash-row-card operational-issue">
                <h2 className="row-link__title">{issue.venueAddress}</h2>
                <p>{issue.description}</p>
                <dl className="dash-figures dash-facts">
                  <div><dt>Affected period</dt><dd>{periodLabel(issue)}</dd></div>
                  <div><dt>Reported</dt><dd>{formatEventDateTime(issue.createdAt)}</dd></div>
                </dl>
                {issue.overlappingEvents.length > 0 ? (
                  <div>
                    <h3 className="dash-section-label">Overlapping managed events</h3>
                    <ul className="dash-queue">
                      {issue.overlappingEvents.map(event => (
                        <li key={event.eventId}>
                          <RowLink to={`/coordinator/events/${event.eventId}`} className="dash-queue__row" title={event.eventName}>
                            <p>Occupied {formatEventDateTime(event.effectiveStart ?? event.startDatetime)} – {formatEventDateTime(event.effectiveEnd ?? event.endDatetime)}</p>
                          </RowLink>
                        </li>
                      ))}
                    </ul>
                    <p className="field-hint">
                      Existing bookings remain unchanged. Review and adjust arrangements separately.
                    </p>
                  </div>
                ) : (
                  <p className="field-hint">No event with an approved booking overlaps this period.</p>
                )}
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
