import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { ApiClientError } from '../../../lib/apiClient'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { TS } from '../shared/faultyEquipment'
import { describeWhen } from '../shared/format'
import { ResourceView } from '../../../components/ui/ResourceView'
import { useResource } from '../../../lib/useResource'
import { fetchChangeReview, recordOutcome } from './changeReviewApi'
import { OUTCOME_LABELS, type ChangeReviewDetail, type ComparedValues, type ReviewOutcome } from './changeReview.types'
import { ReviewBadge } from './ChangeReviewsPage'
import '../technicalSupport.css'
import '../reservations/fulfilment.css'
import './changeReviews.css'

const MAX_NOTE = 2000

function shown(value: string | null, time: boolean): string {
  if (value === null) return 'None'
  return time ? formatEventDateTime(value) : value
}

function Comparison({ previous, updated }: { previous: ComparedValues; updated: ComparedValues }) {
  const rows: { label: string; before: string; after: string }[] = []
  const add = (label: string, before: string | null, after: string | null, time: boolean) => {
    const same = time && before && after ? new Date(before).getTime() === new Date(after).getTime() : before === after
    if (!same) rows.push({ label, before: shown(before, time), after: shown(after, time) })
  }
  add('Start', previous.start, updated.start, true)
  add('End', previous.end, updated.end, true)
  add('Equipment requirements', previous.equipmentRequirements, updated.equipmentRequirements, false)
  return (
    <table className="change-review-table">
      <thead>
        <tr><th scope="col">Detail</th><th scope="col">Before</th><th scope="col">After</th></tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.label}><th scope="row">{row.label}</th><td>{row.before}</td><td>{row.after}</td></tr>
        ))}
      </tbody>
    </table>
  )
}

/** TS11 AC10, AC11: what changed, the event's reservations with the affected
 * ones flagged, and Technical Support's review outcome. */
export function ChangeReviewPage() {
  const { reviewId = '' } = useParams<{ reviewId: string }>()
  const review = useResource(() => fetchChangeReview(reviewId), reviewId)

  return (
    <ResourceView resource={review} loadingText="Loading the change…"
      errorText="Could not load this equipment change review."
      emptyText="This change review could not be found."
      isEmpty={() => false}>
      {(loaded) => <ReviewWorkspace key={loaded.reviewId} initial={loaded} />}
    </ResourceView>
  )
}

function ReviewWorkspace({ initial }: { initial: ChangeReviewDetail }) {
  const [review, setReview] = useState(initial)
  const [outcome, setOutcome] = useState<ReviewOutcome | null>(null)
  const [note, setNote] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!outcome) {
      setFormError('Choose a review outcome.')
      return
    }
    if (outcome === 'needs_coordinator_action' && note.trim() === '') {
      setFormError('Say what the coordinator needs to do.')
      return
    }
    if (note.trim().length > MAX_NOTE) {
      setFormError(`The note can be at most ${MAX_NOTE} characters.`)
      return
    }
    setFormError(null)
    setSaving(true)
    try {
      setReview(await recordOutcome(review.reviewId, outcome, note))
    } catch (error) {
      setFormError(error instanceof ApiClientError && error.message
        ? error.message
        : 'Could not save the review. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="page request-page">
      <Link className="request-page__back" to={`${TS}/change-reviews`}><span aria-hidden="true">‹ </span>All change reviews</Link>
      <header className="card tech-card request-head">
        <div className="request-head__title">
          <p className="field-hint">Change review</p>
          <h1>{review.eventName}</h1>
          <span className="request-head__badges"><ReviewBadge review={review} /></span>
        </div>
        <dl className="request-head__facts">
          <div><dt>When (now)</dt><dd>{describeWhen(review.eventStart, review.eventEnd)}</dd></div>
          <div><dt>Coordinator</dt><dd>{review.coordinatorName ?? 'Not assigned'}</dd></div>
          <div><dt>Change approved</dt><dd>{formatEventDateTime(review.changedAt)}</dd></div>
        </dl>
      </header>

      <Card className="tech-card">
        <h2>What changed</h2>
        <Comparison previous={review.previous} updated={review.updated} />
        {review.requirementsChanged && (
          <Notice tone="info">The equipment requirements changed. Check the request still matches them.</Notice>
        )}
      </Card>

      <Card className="tech-card">
        <h2>Reservations for this event</h2>
        {review.reservations.length === 0 ? (
          <p className="field-hint">This event has no equipment reservations.</p>
        ) : (
          <ul className="change-review-reservations">
            {review.reservations.map((r) => (
              <li key={r.logId} data-flagged={r.flagged}>
                <span>
                  {r.quantity} × {r.equipmentName}{r.serialNumber ? ` (${r.serialNumber})` : ''}
                  {' '}— {formatEventDateTime(r.loanedFrom)} to {formatEventDateTime(r.loanedUntil)}
                </span>
                {r.flagged && <span className="tech-badge tech-badge--warning">Needs review: no longer covers the event</span>}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card className="tech-card">
        <h2>Review</h2>
        {review.needsReview ? (
          <form onSubmit={submit} noValidate>
            <fieldset className="change-review-outcomes">
              <legend>Outcome</legend>
              <p className="field-hint">Still suitable clears the coordinator's equipment flag, and only while every reservation covers the event's time. Needs coordinator action keeps it until they revise the request.</p>
              {(Object.keys(OUTCOME_LABELS) as ReviewOutcome[]).map((value) => (
                <label key={value}>
                  <input
                    type="radio"
                    name="outcome"
                    value={value}
                    checked={outcome === value}
                    onChange={() => setOutcome(value)}
                  />
                  {OUTCOME_LABELS[value]}
                </label>
              ))}
            </fieldset>
            <div className="field">
              <label htmlFor="review-note">Note for the coordinator</label>
              <textarea
                id="review-note"
                value={note}
                maxLength={MAX_NOTE}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
              />
            </div>
            {formError && <Notice tone="danger">{formError}</Notice>}
            <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Record review'}</Button>
          </form>
        ) : (
          <Notice tone="success">
            {OUTCOME_LABELS[review.outcome ?? 'still_suitable']}
            {review.reviewedByName ? ` — reviewed by ${review.reviewedByName}` : ''}
            {review.reviewedAt ? ` on ${formatEventDateTime(review.reviewedAt)}` : ''}.
            {review.outcomeNote ? ` ${review.outcomeNote}` : ''}
          </Notice>
        )}
      </Card>
    </section>
  )
}
