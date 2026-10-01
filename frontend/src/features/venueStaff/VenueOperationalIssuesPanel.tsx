import { useEffect, useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueOperationalIssueDto } from '../../types/venueOperationalIssue'
import { formatEventDateTime } from './formatEventDateTime'

const MAX_DESCRIPTION_LENGTH = 2000

function toApiDateTime(value: string): string | null {
  return value ? new Date(value).toISOString() : null
}

function periodLabel(issue: VenueOperationalIssueDto): string {
  if (!issue.affectedFrom || !issue.affectedUntil) return 'Period not specified'
  return `${formatEventDateTime(issue.affectedFrom)} – ${formatEventDateTime(issue.affectedUntil)}`
}

export function VenueOperationalIssuesPanel({ venueId }: { venueId: string }) {
  const [issues, setIssues] = useState<VenueOperationalIssueDto[] | null>(null)
  const [description, setDescription] = useState('')
  const [affectedFrom, setAffectedFrom] = useState('')
  const [affectedUntil, setAffectedUntil] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let active = true
    apiClient.get<VenueOperationalIssueDto[]>(
      `/venues/${venueId}/operational-issues`, { cache: 'no-store' },
    ).then(data => {
      if (active) setIssues(data)
    }).catch((err: unknown) => {
      if (active) setError(err instanceof ApiClientError ? err.message : 'Could not load operational issues.')
    })
    return () => { active = false }
  }, [venueId])

  async function loadIssues() {
    setError(null)
    try {
      setIssues(await apiClient.get<VenueOperationalIssueDto[]>(
        `/venues/${venueId}/operational-issues`, { cache: 'no-store' },
      ))
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not load operational issues.')
    }
  }

  async function submit() {
    setError(null)
    setSuccess(false)
    const trimmed = description.trim()
    if (!trimmed) {
      setError('Issue description is required.')
      return
    }
    if ((affectedFrom && !affectedUntil) || (!affectedFrom && affectedUntil)) {
      setError('Enter both the affected start and end date/time.')
      return
    }
    if (affectedFrom && affectedUntil && new Date(affectedUntil) <= new Date(affectedFrom)) {
      setError('Affected end date/time must be after the start date/time.')
      return
    }
    if (trimmed.length > MAX_DESCRIPTION_LENGTH) {
      setError(`Issue description must be at most ${MAX_DESCRIPTION_LENGTH} characters.`)
      return
    }

    setSaving(true)
    try {
      const saved = await apiClient.post<VenueOperationalIssueDto>(
        `/venues/${venueId}/operational-issues`,
        {
          description: trimmed,
          affectedFrom: toApiDateTime(affectedFrom),
          affectedUntil: toApiDateTime(affectedUntil),
        },
      )
      setIssues(current => [saved, ...(current ?? [])])
      setDescription('')
      setAffectedFrom('')
      setAffectedUntil('')
      setSuccess(true)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not save the operational issue.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section aria-labelledby="operational-issues-heading">
      <div className="venue-details-page__section-header">
        <h2 id="operational-issues-heading">Operational issues</h2>
        <Button variant="ghost" onClick={() => void loadIssues()} disabled={saving}>Refresh issues</Button>
      </div>
      <Card className="venue-operational-issues__form">
        <h3>Report operational issue</h3>
        <label htmlFor="operational-issue-description">Issue description</label>
        <textarea id="operational-issue-description" rows={3} value={description}
          maxLength={MAX_DESCRIPTION_LENGTH} onChange={event => setDescription(event.target.value)}
          placeholder="Describe the problem affecting this venue." />
        <span className="field-hint">{description.trim().length}/{MAX_DESCRIPTION_LENGTH}</span>
        <div className="venue-operational-issues__period">
          <label htmlFor="operational-issue-from">Affected from (optional)</label>
          <input id="operational-issue-from" type="datetime-local" value={affectedFrom}
            onChange={event => setAffectedFrom(event.target.value)} />
          <label htmlFor="operational-issue-until">Affected until (optional)</label>
          <input id="operational-issue-until" type="datetime-local" value={affectedUntil}
            onChange={event => setAffectedUntil(event.target.value)} />
        </div>
        {error ? <p role="alert">{error}</p> : null}
        {success ? <p role="status">Operational issue reported successfully.</p> : null}
        <Button onClick={() => void submit()} disabled={saving}>
          {saving ? 'Saving…' : 'Report issue'}
        </Button>
      </Card>
      {issues === null && !error ? <p role="status">Loading operational issues…</p> : null}
      {issues && issues.length === 0 ? <p>No operational issues reported.</p> : null}
      {issues && issues.length > 0 ? (
        <ul className="venue-operational-issues__list">
          {issues.map(issue => (
            <li key={issue.issueId}>
              <Card>
                <p>{issue.description}</p>
                <p><strong>Affected period:</strong> {periodLabel(issue)}</p>
                <p className="field-hint">Reported {formatEventDateTime(issue.createdAt)}</p>
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
