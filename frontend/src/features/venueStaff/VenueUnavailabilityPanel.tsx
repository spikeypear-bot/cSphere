import { useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useVenueRead } from './useVenueRead'
import { formatEventTimeRange as range } from './formatEventDateTime'
import { singaporeToday, unavailabilityDateErrors } from './unavailabilityValidation'
import '../../components/skeleton.css'
import './VenueUnavailability.css'

export interface UnavailablePeriod {
  unavailabilityId: string; startDateTime: string; endDateTime: string; reason: string
}
export interface ScheduleBooking {
  bookingId: string; eventName: string; status: string; startDateTime: string; endDateTime: string
  effectiveStart: string; effectiveEnd: string
}
interface Preview { affectedBookings: ScheduleBooking[]; previewToken: string }
export interface OccupancySettings { setupMinutes: number; turnaroundMinutes: number }
export interface VenueSchedule {
  settings: OccupancySettings; bookings: ScheduleBooking[]; unavailablePeriods: UnavailablePeriod[]
}
const message = (error: unknown, fallback: string) => error instanceof ApiClientError ? error.message : fallback

export function VenueUnavailabilityPanel({ venueId, onRecorded }: { venueId: string; onRecorded: () => void }) {
  const periods = useVenueRead<UnavailablePeriod[]>(`/venues/${venueId}/unavailability`)
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const dateErrors = unavailabilityDateErrors(start, end)
  const startError = start || errors.start ? dateErrors.start : undefined
  const endError = end || errors.end ? dateErrors.end : undefined
  const body = () => ({ startDateTime: `${start.length === 16 ? `${start}:00` : start}+08:00`,
    endDateTime: `${end.length === 16 ? `${end}:00` : end}+08:00`, reason: reason.trim() })

  async function save(review: Preview) {
    // Recheck at confirmation too: a form can remain open across Singapore midnight.
    const dates = unavailabilityDateErrors(start, end)
    if (Object.keys(dates).length) { setErrors(dates); setPreview(null); return }
    try {
      await apiClient.post(`/venues/${venueId}/unavailability`, {
        ...body(), previewToken: review.previewToken,
        acknowledgedBookingIds: review.affectedBookings.map(b => b.bookingId),
      })
      setPreview(null); setStart(''); setEnd(''); setReason(''); setSuccess(true)
      periods.retry(); onRecorded()
    } catch (e) {
      setPreview(null)
      setError(message(e, 'Could not save unavailability. Review and try again.'))
    }
  }

  async function review() {
    const next = unavailabilityDateErrors(start, end)
    if (!reason.trim()) next.reason = 'Reason is required.'
    else if (reason.trim().length > 2000) next.reason = 'Reason must be at most 2000 characters.'
    setErrors(next); setError(''); setSuccess(false)
    if (Object.keys(next).length) return
    setBusy(true)
    try {
      const result = await apiClient.post<Preview>(`/venues/${venueId}/unavailability/preview`, body())
      if (result.affectedBookings.length) setPreview(result)
      else await save(result)
    } catch (e) { setError(message(e, 'Could not check affected bookings. Nothing was saved.')) }
    finally { setBusy(false) }
  }

  return <section aria-labelledby="unavailability-heading" className="venue-unavailability">
    <h2 id="unavailability-heading">Unavailability</h2>
    <Card className="feature-skeleton__body venue-unavailability__card">
      <p className="venue-unavailability__intro">Record when this venue cannot be used. All times are Singapore time (UTC+08:00).</p>
      <form onSubmit={e => { e.preventDefault(); void review() }} noValidate>
        <fieldset className="venue-unavailability__fields" disabled={busy || preview !== null}>
          <div className="venue-unavailability__dates">
          <div className={`field${startError ? ' field--invalid' : ''}`}>
          <label htmlFor="unavailable-start">Start date/time</label>
          <input id="unavailable-start" type="datetime-local" required min={`${singaporeToday()}T00:00`} value={start} onChange={e => setStart(e.target.value)}
            aria-invalid={!!startError} aria-describedby="unavailable-start-error" />
          <span id="unavailable-start-error" className="field-error" role={startError ? 'alert' : undefined}>{startError}</span>
          </div>
          <div className={`field${endError ? ' field--invalid' : ''}`}>
          <label htmlFor="unavailable-end">End date/time</label>
          <input id="unavailable-end" type="datetime-local" required min={start > `${singaporeToday()}T00:00` ? start : `${singaporeToday()}T00:00`} value={end} onChange={e => setEnd(e.target.value)}
            aria-invalid={!!endError} aria-describedby="unavailable-end-error" />
          <span id="unavailable-end-error" className="field-error" role={endError ? 'alert' : undefined}>{endError}</span>
          </div>
          </div>
          <div className={`field${errors.reason ? ' field--invalid' : ''}`}>
          <label htmlFor="unavailable-reason">Reason</label>
          <textarea id="unavailable-reason" required rows={3} maxLength={2000} value={reason} onChange={e => setReason(e.target.value)}
            placeholder="e.g. Scheduled maintenance, renovation, safety restrictions, or an internal activity."
            aria-invalid={!!errors.reason} aria-describedby="unavailable-reason-error" />
          <span id="unavailable-reason-error" className="field-error" role={errors.reason ? 'alert' : undefined}>{errors.reason}</span>
          </div>
          {error && <div className="field"><p role="alert" className="field-error">{error}</p></div>}
          <div className="venue-unavailability__actions">
          <Button type="submit" disabled={!!(start && dateErrors.start) || !!(end && dateErrors.end)}>{busy ? 'Checking…' : 'Record Unavailability'}</Button>
          </div>
        </fieldset>
      </form>
      {preview && <div className="venue-unavailability__warning" role="alert" aria-label="Affected bookings">
        <h3>Existing bookings will be affected</h3>
        <p>These events will not be cancelled automatically. Alternative venue arrangements will be required.</p>
        <ul>{preview.affectedBookings.map(b => <li key={b.bookingId}>
          <strong>{b.eventName}</strong> — {range(b.startDateTime, b.endDateTime)} · {b.status}
          <p>Including setup and turnaround: {range(b.effectiveStart, b.effectiveEnd)}</p>
        </li>)}</ul>
        <div className="venue-unavailability__actions">
        <Button variant="secondary" disabled={busy} onClick={() => setPreview(null)}>Return to form</Button>
        <Button disabled={busy} onClick={async () => { setBusy(true); await save(preview); setBusy(false) }}>
          {busy ? 'Saving…' : 'Confirm recording unavailability'}
        </Button>
        </div>
      </div>}
      {success && <p role="status">Unavailability recorded. Affected bookings remain intact and assigned coordinators have been notified.</p>}
    </Card>
    {periods.error ? <div className="field"><p className="field-error" role="alert">{periods.error}</p><Button onClick={periods.retry}>Retry unavailability</Button></div>
      : !periods.data ? <p role="status">Loading unavailability…</p>
      : !periods.data.length ? <p>No unavailable periods recorded.</p>
      : <ul className="unavailability-periods">{periods.data.map(p => <li key={p.unavailabilityId}>
        <Card className="feature-skeleton__body venue-unavailability__period">
          <h3>{range(p.startDateTime, p.endDateTime)}</h3><p>{p.reason}</p>
        </Card>
      </li>)}</ul>}
  </section>
}

export function VenueOccupancySettings({ venueId }: { venueId: string }) {
  const read = useVenueRead<OccupancySettings>(`/venues/${venueId}/occupancy-settings`)
  return read.error ? <p role="alert">{read.error} <Button onClick={read.retry}>Retry setup settings</Button></p>
    : read.data ? <SettingsForm key={`${venueId}-${read.data.setupMinutes}-${read.data.turnaroundMinutes}`} venueId={venueId} settings={read.data} />
    : <p>Loading setup settings…</p>
}

function SettingsForm({ venueId, settings }: { venueId: string; settings: OccupancySettings }) {
  const [setup, setSetup] = useState(String(settings.setupMinutes))
  const [turnaround, setTurnaround] = useState(String(settings.turnaroundMinutes))
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState('')
  return <details className="venue-unavailability"><summary>Setup and turnaround settings</summary>
    <p>These buffers apply to availability checks. Configure them before booking this venue; active bookings prevent changes.</p>
    <form onSubmit={async e => {
      e.preventDefault(); setBusy(true); setFeedback('')
      try {
        await apiClient.put(`/venues/${venueId}/occupancy-settings`, { setupMinutes: Number(setup), turnaroundMinutes: Number(turnaround) })
        setFeedback('Setup and turnaround saved.')
      } catch (e) { setFeedback(message(e, 'Could not save settings.')) }
      finally { setBusy(false) }
    }}>
      <fieldset disabled={busy}>
        <label htmlFor="venue-setup">Setup (minutes)</label>
        <input id="venue-setup" required type="number" min={0} max={10080} step={1} value={setup} onChange={e => setSetup(e.target.value)} />
        <label htmlFor="venue-turnaround">Turnaround (minutes)</label>
        <input id="venue-turnaround" required type="number" min={0} max={10080} step={1} value={turnaround} onChange={e => setTurnaround(e.target.value)} />
        <Button type="submit">Save setup and turnaround</Button>
      </fieldset>
      {feedback && <p role="status">{feedback}</p>}
    </form>
  </details>
}
