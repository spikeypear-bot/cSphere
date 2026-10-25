import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { ApiClientError } from '../../../lib/apiClient'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { fetchChangeReview, recordOutcome } from './changeReviewApi'
import { OUTCOME_LABELS, type ChangeReviewDetail, type ComparedValues, type ReviewOutcome } from './changeReview.types'
import '../technicalSupport.css'
import './changeReviews.css'

const MAX_NOTE = 2000

type State =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'loaded'; review: ChangeReviewDetail }

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
  const { reviewId = '' } = useParams()
  const [state, setState] = useState<State>({ status: 'loading' })
  const [outcome, setOutcome] = useState<ReviewOutcome | null>(null)
  const [note, setNote] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(() => {
    fetchChangeReview(reviewId)
      .then((review) => setState({ status: 'loaded', review }))
      .catch(() => setState({ status: 'error' }))
  }, [reviewId])

  useEffect(() => { load() }, [load])

  function retry() {
    setState({ status: 'loading' })
    load()
  }

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
      const review = await recordOutcome(reviewId, outcome, note)
      setState({ status: 'loaded', review })
    } catch (error) {
      setFormError(error instanceof ApiClientError && error.message
        ? error.message
        : 'Could not save the review. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="page">
      <PageHeader
        title="Equipment change review"
        description="An approved change to this event may affect its equipment. Nothing is moved automatically."
      />
      <p><Link to="/technical-support/change-reviews">All equipment change reviews</Link></p>

      {state.status === 'loading' && <p>Loading the change…</p>}
      {state.status === 'error' && (
        <Card className="tech-card">
          <Notice tone="danger">Could not load this equipment change review.</Notice>
          <Button variant="secondary" onClick={retry}>Try again</Button>
        </Card>
      )}
      {state.status === 'loaded' && (
        <>
          <Card className="tech-card">
            <h2>{state.review.eventName}</h2>
            <p>
              Now {formatEventDateTime(state.review.eventStart)} – {formatEventDateTime(state.review.eventEnd)}.
              {' '}Coordinator: {state.review.coordinatorName ?? 'not assigned'}.
              {' '}Change approved {formatEventDateTime(state.review.changedAt)}.
            </p>
            <Comparison previous={state.review.previous} updated={state.review.updated} />
            {state.review.requirementsChanged && (
              <Notice tone="info">The equipment requirements changed. Check the request still matches them.</Notice>
            )}
          </Card>

          <Card className="tech-card">
            <h2>Reservations for this event</h2>
            {state.review.reservations.length === 0 ? (
              <p>This event has no equipment reservations.</p>
            ) : (
              <ul className="change-review-reservations">
                {state.review.reservations.map((r) => (
                  <li key={r.logId} data-flagged={r.flagged}>
                    <span>
                      {r.quantity} × {r.equipmentName}{r.serialNumber ? ` (${r.serialNumber})` : ''}
                      {' '}— {formatEventDateTime(r.loanedFrom)} to {formatEventDateTime(r.loanedUntil)}
                    </span>
                    {r.flagged && <span className="chip change-review-chip--open">Needs review: no longer covers the event</span>}
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card className="tech-card">
            <h2>Review</h2>
            {state.review.needsReview ? (
              <form onSubmit={submit} noValidate>
                <fieldset className="change-review-outcomes">
                  <legend>Outcome</legend>
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
                <label htmlFor="review-note">Note for the coordinator</label>
                <textarea
                  id="review-note"
                  value={note}
                  maxLength={MAX_NOTE}
                  onChange={(e) => setNote(e.target.value)}
                  rows={3}
                />
                {formError && <Notice tone="danger">{formError}</Notice>}
                <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Record review'}</Button>
              </form>
            ) : (
              <Notice tone="success">
                {OUTCOME_LABELS[state.review.outcome ?? 'still_suitable']}
                {state.review.reviewedByName ? ` — reviewed by ${state.review.reviewedByName}` : ''}
                {state.review.reviewedAt ? ` on ${formatEventDateTime(state.review.reviewedAt)}` : ''}.
                {state.review.outcomeNote ? ` ${state.review.outcomeNote}` : ''}
              </Notice>
            )}
          </Card>
        </>
      )}
    </section>
  )
}
