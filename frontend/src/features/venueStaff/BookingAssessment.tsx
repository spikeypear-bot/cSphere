import { BOOKING_STATUS_LABELS, type VenueBookingStatus } from '../../types/venueBookingStatus'
import { useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Link } from 'react-router-dom'
import { apiClient } from '../../lib/apiClient'
import { useVenueRead } from './useVenueRead'
import { formatEventDateTime } from './formatEventDateTime'
import './VenueRequirementReviews.css'

type Assessment = {
  bookingId: string; status: string; canReassess?: boolean
  checks: { key: string; status: string; detail: string }[]
  history: { assessmentId: string; outcome: string; source: string; reason: string; actorName: string | null; createdAt: string }[]
}
export function BookingAssessment({ bookingId, eventId, onChanged }: { bookingId?: string; eventId?: string; onChanged?: () => void }) {
  const path = bookingId ? `/venue-bookings/${bookingId}/assessment` : `/events/${eventId}/venue-assessments`
  const { data, error, retry } = useVenueRead<Assessment | Assessment[]>(path, false, 5000, true)
  if (error && !data) return <div role="alert">Could not load venue assessments. <Button onClick={retry}>Retry assessments</Button></div>
  if (!data) return null
  const views = Array.isArray(data) ? data : [data]
  if (views.some(v => !v || !Array.isArray(v.history) || !Array.isArray(v.checks))) return <div role="alert">Could not load venue assessments. <Button onClick={retry}>Retry assessments</Button></div>
  return <>{error && <div role="alert">Could not refresh venue assessments. <Button onClick={retry}>Retry assessments</Button></div>}{views.filter(v => v.status === 'attention' || v.history.length > 0).map(v =>
    <AssessmentCard key={v.bookingId} view={v} staff={!!bookingId} onSaved={() => { retry(); onChanged?.() }} eventId={eventId} disabled={!!error} />)}</>
}
function AssessmentCard({ view, staff, onSaved, eventId, disabled }: { view: Assessment; staff: boolean; onSaved: () => void; eventId?: string; disabled: boolean }) {
  const [revision, setRevision] = useState(view.history[0]?.assessmentId)
  const stale = revision !== view.history[0]?.assessmentId
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const attention = view.status === 'attention'
  const failed = view.checks.filter(c => c.status === 'attention')
  async function save(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null)
    try {
      await apiClient.post(`/venue-bookings/${view.bookingId}/reassess`, { assessmentId: revision, note })
      setNote(''); onSaved()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save reassessment. Please retry.'); onSaved() }
    finally { setBusy(false) }
  }
  return <Card role="article" className="venue-reviews venue-assessment venue-card-preview venue-reviews__item" aria-label="Booking suitability assessment">
    <h2>Venue Suitability Assessment</h2>
    <p className="venue-card-preview__id">Booking ID: {view.bookingId}</p>
    <span className="venue-card-preview__tag booking-status-tag" data-booking-status={view.status}>{attention ? 'Requires attention' : view.status === 'approved' && view.history[0]?.outcome === 'restored' ? 'Approval restored' : BOOKING_STATUS_LABELS[view.status as VenueBookingStatus]}</span>
    {attention && <div role="status"><p>Reassessment required. The reserved period is unchanged.</p>
      {failed.length ? <ul>{failed.map(c => <li key={c.key}>{c.detail}</li>)}</ul> : <p>No issues detected by current checks. Review the arrangements before restoring approval.</p>}
      {failed.length === 0 && view.history[0]?.outcome === 'attention' && <p>Previous issue: {view.history[0].reason}</p>}
      {failed.length > 0 && view.history[0]?.reason.includes('Supported layouts changed') && <p>Supported layouts changed. Review the requested room layout and written venue requirements.</p>}
    </div>}
    {staff && attention && view.canReassess !== false && <form aria-label="Booking reassessment" onSubmit={e => { void save(e) }}><fieldset disabled={busy || disabled}>
      <legend>Reassess Booking</legend>
      <label>Reassessment comment<textarea required maxLength={2000} value={note} onChange={e => setNote(e.target.value)} rows={3} /></label>
      {stale && <p role="alert">The assessment changed. Review the current issues, then <Button type="button" onClick={() => { setRevision(view.history[0]?.assessmentId); setNote(''); setError(null) }}>Restart reassessment</Button>.</p>}
      <Button type="submit" disabled={busy || stale || failed.length > 0}>{busy ? 'Saving…' : 'Restore approval'}</Button>
    </fieldset></form>}
    {!staff && attention && <Link className="button button--secondary" to={`/coordinator/events/${eventId}/venue-booking?replacesBookingId=${view.bookingId}`}>Find replacement venue</Link>}
    {error && <p role="alert">{error}</p>}
    <details><summary>Assessment history</summary><ul>{view.history.map(h => <li key={h.assessmentId}>
      <strong>{h.outcome === 'attention' ? 'Requires attention' : 'Approval restored'}</strong> · {formatEventDateTime(h.createdAt)}
      {h.actorName && ` · ${h.actorName}`}<p>{h.reason}</p>
    </li>)}</ul></details>
  </Card>
}
