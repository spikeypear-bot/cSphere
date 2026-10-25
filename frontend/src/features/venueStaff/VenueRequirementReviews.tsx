import { useState } from 'react'
import { Card } from '../../components/ui/Card'
import './VenueCataloguePage.css'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueRequirementReviewView } from '../../types/venueRequirementReview'
import { ACCESSIBILITY_LABELS, type AccessibilityFeature } from '../../types/eventRequest'
import { venueFacilityLabels, venueLayoutLabels, type Facility, type VenueLayout } from '../../types/venue'
import { formatEventDateTime } from './formatEventDateTime'
import { useVenueRead } from './useVenueRead'
import './VenueRequirementReviews.css'

const OUTCOME = { pending: 'Review Required', suitable: 'Remains suitable', action_required: 'Coordinator action required' }
function show(field: string, value: unknown): string {
  if (value == null || value === '') return 'None specified'
  if (field.endsWith('Datetime') && typeof value === 'string') return formatEventDateTime(value)
  if (Array.isArray(value)) return value.map(v => field === 'accessibilityNeeds'
    ? ACCESSIBILITY_LABELS[v as AccessibilityFeature] ?? v : field === 'requiredFacilities'
      ? venueFacilityLabels[v as Facility] ?? v : String(v)).join(', ') || 'None specified'
  return String(value)
}
function period(start: string, end: string) { return `${formatEventDateTime(start)} – ${formatEventDateTime(end)}` }

/** VS17: shared historical view; staff actions remain on the staff booking page. */
export function VenueRequirementReviews({ bookingId, eventId }: { bookingId?: string; eventId?: string }) {
  const staff = !!bookingId
  const path = staff ? `/venue-bookings/${bookingId}/requirement-reviews` : `/events/${eventId}/venue-reviews`
  const { data, loading, error, retry, replaceData } = useVenueRead<VenueRequirementReviewView[]>(path, false, 15000, true)
  const valid = (loading && data == null) || (Array.isArray(data) && data.every(view => view?.review && typeof view.review.reviewId === 'string' && Array.isArray(view.changes) && Array.isArray(view.checks)))
  const reviews = valid ? data : null
  const loadError = error ?? (valid ? null : 'Please try again.')
  if (!loading && !loadError && reviews?.length === 0) return null
  return <section className="venue-reviews" aria-label="Venue requirement reviews" aria-busy={loading}>
    <h2>Venue Requirement Reviews</h2>
    {loading && <p role="status">Loading requirement reviews…</p>}
    {loadError && <div role="alert"><p>Could not load requirement reviews. {loadError}</p><Button onClick={retry}>Retry requirement reviews</Button></div>}
    {reviews?.map(view => <Review key={`${path}:${view.review.reviewId}`} view={view} staff={staff} disabled={!!loadError || loading}
      onRefresh={retry} onSaved={saved => replaceData(reviews.map(v => v.review.reviewId === saved.review.reviewId ? saved : v))} />)}
  </section>
}

function Review({ view, staff, disabled, onSaved, onRefresh }: { view: VenueRequirementReviewView; staff: boolean; disabled: boolean;
  onSaved: (view: VenueRequirementReviewView) => void; onRefresh: () => void }) {
  const { review } = view
  const invalidated = view.bookingStatus === 'attention'
  const historical = view.outcomeCurrent === false
  const displayedOutcome = invalidated ? 'Requires attention' : historical && review.status !== 'pending' ? 'Previous review' : OUTCOME[review.status]
  const reservationChanged = Date.parse(view.reservedStart) !== Date.parse(review.reservedStart)
    || Date.parse(view.reservedEnd) !== Date.parse(review.reservedEnd)
  const durationsChanged = view.setupDurationMinutes !== review.setupDurationMinutes
    || view.turnaroundDurationMinutes !== review.turnaroundDurationMinutes
  const [outcome, setOutcome] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save(event: React.FormEvent) {
    event.preventDefault(); setError(null)
    if (!outcome) { setError('Choose a review outcome.'); return }
    if (outcome === 'action_required' && !note.trim()) { setError('Explain what the coordinator needs to do.'); return }
    if (note.trim().length > 2000) { setError('Review comment must be at most 2000 characters.'); return }
    setSaving(true)
    try {
      const saved = await apiClient.post<VenueRequirementReviewView>(`/venue-bookings/${review.bookingId}/requirement-reviews/${review.reviewId}/decision`, { outcome, note: note.trim() || null })
      onSaved(saved)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'The review could not be saved. Please try again.')
      if (err instanceof ApiClientError && err.status === 409) onRefresh()
    } finally { setSaving(false) }
  }
  return <Card role="article" className="feature-skeleton__body venue-card-preview venue-reviews__item" aria-label={`Requirement review for ${review.venueAddress}`}>
    <div className="venue-card-preview__header venue-reviews__header">
      <div className="venue-reviews__metadata">
        <p className="venue-card-preview__id" aria-label="Booking ID">Booking ID: {review.bookingId}</p>
        <p className="venue-card-preview__id">Change approved {formatEventDateTime(review.approvedAt)} | Coordinator at approval: {review.coordinatorName ?? 'Unassigned'}{review.coordinatorEmail ? ` (${review.coordinatorEmail})` : ''}</p>
      </div>
      <span className="venue-card-preview__tag" data-review-status={invalidated ? 'action_required' : review.status}>{displayedOutcome}</span>
    </div>
    <h3 className="venue-card-preview__title venue-reviews__title" aria-label={`${review.venueAddress} — ${displayedOutcome}`}>{review.venueAddress}</h3>
    <div className="venue-reviews__table"><h3 id={`requirement-changes-${review.reviewId}`}>Approved Requirement Changes</h3><table aria-labelledby={`requirement-changes-${review.reviewId}`}>
      <thead><tr><th scope="col">Requirement</th><th scope="col">Previous</th><th scope="col" className="venue-reviews__new">New</th></tr></thead>
      <tbody>{view.changes.map(c => <tr key={c.field}><th scope="row">{c.label}</th><td>{show(c.field,c.before)}</td><td className="venue-reviews__new"><strong>{show(c.field,c.after)}</strong>
        {c.field === 'venueRequirements' && view.suitability && <div className="venue-reviews__layout-context">
          <p>Venue supports: {view.suitability.supportedLayouts.map(layout => venueLayoutLabels[layout as VenueLayout] ?? layout).join(', ') || 'Not recorded'}</p>
          <p>Staff assessment needed for written requirements and layout.</p>
        </div>}
      </td></tr>)}</tbody>
    </table></div>
    <dl className="venue-card-preview__information venue-reviews__information">
      <div><dt>Actual reserved times</dt><dd>{period(view.reservedStart,view.reservedEnd)}</dd></div>
      <div><dt>Setup / Turnaround</dt><dd>{view.setupDurationMinutes} / {view.turnaroundDurationMinutes} minutes</dd></div>
      {view.suitability && <div><dt>Occupied period</dt><dd>{period(view.suitability.occupiedStart,view.suitability.occupiedEnd)} · Singapore time
        <p className="venue-reviews__manual">Includes setup and turnaround.</p></dd></div>}
      {reservationChanged && <div><dt>Reserved when the change was approved</dt><dd>{period(review.reservedStart,review.reservedEnd)}</dd></div>}
      {durationsChanged && <div><dt>Setup / Turnaround at approval</dt><dd>{review.setupDurationMinutes} / {review.turnaroundDurationMinutes} minutes</dd></div>}
    </dl>
    <SuitabilitySummary view={view} />
    {review.status !== 'pending' && <div className="venue-reviews__outcome" role="status"><p>{(invalidated || historical) && <span>Previous decision: {OUTCOME[review.status]}. </span>}<strong>Reviewed by {review.reviewerName}</strong>, {review.reviewedAt ? formatEventDateTime(review.reviewedAt) : ''}.</p>
      {review.reviewNote && <p>{review.reviewNote}</p>}
    </div>}
    {staff && view.canReview && <form onSubmit={e => { void save(e) }}>
      <fieldset disabled={disabled || saving}><legend>Record Review Outcome</legend>
        <label>Outcome<select value={outcome} onChange={e => setOutcome(e.target.value)}>
          <option value="">Choose an outcome</option><option value="suitable" disabled={view.checks.some(c => c.status === 'attention')}>Remains suitable</option><option value="action_required">Coordinator action required</option>
        </select></label>
        <label>Review comment{outcome === 'action_required' ? ' (required)' : ' (optional)'}<textarea value={note} onChange={e => setNote(e.target.value)} maxLength={2000} rows={3} /></label>
        {view.checks.some(c => c.status === 'attention') && <p>Current checks require attention. Record coordinator action required, or refresh after arrangements are resolved.</p>}
        <p>Your review will be sent to the assigned coordinator.</p>
        <Button type="submit" disabled={saving}>{saving ? 'Saving review…' : 'Save review'}</Button>
      </fieldset>
    </form>}
    {staff && review.status === 'pending' && !view.canReview && <p>{invalidated ? 'Resolve the booking suitability issues before recording this review. Its history is retained.' : 'This booking is no longer active. Its review history is retained.'}</p>}
    {error && <p role="alert">{error}</p>}
  </Card>
}

function SuitabilitySummary({ view }: { view: VenueRequirementReviewView }) {
  const summary = view.suitability
  if (!summary) return <section className="venue-reviews__suitability" aria-label="Venue suitability"><h3>Venue Suitability</h3><p>Suitability summary unavailable.</p></section>
  const attention = summary.checks.some(c => c.status === 'attention')
  return <section className="venue-reviews__suitability" aria-label="Venue suitability">
    <div className="venue-card-preview__header"><h3>Venue Suitability</h3>
      <span className="venue-card-preview__tag" data-review-status={attention ? 'action_required' : 'suitable'}>{attention ? 'Requires attention' : 'No issues detected'}</span>
    </div>
    <div className="venue-reviews__table"><table aria-label="Venue suitability results">
      <thead><tr><th scope="col">Check</th><th scope="col">Result</th></tr></thead>
      <tbody>{summary.checks.map(c => <tr key={c.key} data-attention={c.status === 'attention'}><th scope="row">{c.title}</th><td>{c.detail}</td></tr>)}</tbody>
    </table></div>

  </section>
}
