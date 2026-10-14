import { useState, type FormEvent } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { validMinutes, occupiedPeriod } from '../../lib/bookingTiming'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/fields'
import { Notice } from '../../components/ui/Notice'
import { Card } from '../../components/ui/Card'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'
import type { VenueBookingDto } from '../../types/venueBooking'
import { formatEventTimeRange as range } from './formatEventDateTime'

export function BookingTimingEditor({ booking, onSaved, disabled }: {
  booking: VenueBookingDto; onSaved: (booking: VenueBookingDto) => void; disabled: boolean
}) {
  const timing = booking.timing
  const [setup, setSetup] = useState(String(timing?.setupDurationMinutes ?? 0))
  const [turnaround, setTurnaround] = useState(String(timing?.turnaroundDurationMinutes ?? 0))
  const [note, setNote] = useState(timing?.timingNote ?? '')
  const [noteError, setNoteError] = useState('')
  const [busy, setBusy] = useState(false)
  const [success, setSuccess] = useState('')
  const [error, setError] = useState<Error | null>(null)
  const [loadedTiming, setLoadedTiming] = useState(timing)
  // A refreshed booking must refresh the editable values as well as the summary.
  if (loadedTiming !== timing) {
    setLoadedTiming(timing)
    setSetup(String(timing?.setupDurationMinutes ?? 0))
    setTurnaround(String(timing?.turnaroundDurationMinutes ?? 0))
    setNote(timing?.timingNote ?? '')
    setNoteError('')
  }
  const setupValid = validMinutes(setup)
  const turnaroundValid = validMinutes(turnaround)
  const valid = setupValid && turnaroundValid
  const preview = valid ? occupiedPeriod(timing?.startDatetime ?? booking.event.startDatetime, timing?.endDatetime ?? booking.event.endDatetime, Number(setup), Number(turnaround)) : null
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!valid || busy || disabled || !timing) return
    setNoteError(''); setError(null); setSuccess('')
    if (!note.trim() || note.trim().length > 2000) { setNoteError('Enter a timing reason of 1–2,000 characters.'); return }
    setBusy(true); setError(null); setSuccess('')
    try {
      const saved = await apiClient.put<VenueBookingDto>(`/venue-bookings/${booking.bookingId}/timing`, {
        setupDurationMinutes: Number(setup), turnaroundDurationMinutes: Number(turnaround), timingNote: note.trim(),
        previousSetupDurationMinutes: timing.setupDurationMinutes,
        previousTurnaroundDurationMinutes: timing.turnaroundDurationMinutes,
      })
      onSaved(saved); setSuccess('Booking timings saved. The assigned coordinator has been notified.')
    } catch (cause) { setError(cause instanceof Error ? cause : new Error('Could not save booking timings.')) }
    finally { setBusy(false) }
  }
  if (!timing) return null
  return <Card className="feature-skeleton__body booking-timings-panel">
    <BookingTimingSummary timing={timing} headingLevel={2} />
    {timing.editable !== false && (booking.status === 'pending' || booking.status === 'approved') && <details>
      <summary>Edit booking timings</summary>
      <form onSubmit={save} noValidate>
        <fieldset disabled={busy || disabled}>
          <legend>Booking-specific durations</legend>
          <div className={setupValid ? 'field' : 'field field--invalid'}><label htmlFor="booking-setup">Setup (minutes)</label>
            <input id="booking-setup" type="number" min="0" max="10080" step="1" required value={setup}
              aria-invalid={!setupValid} aria-describedby={!setupValid ? 'booking-setup-error' : undefined}
              onChange={e => setSetup(e.target.value)} />
            {!setupValid && <span id="booking-setup-error" className="field-error" role="alert">Enter whole minutes from 0 to 10,080.</span>}
          </div>
          <div className={turnaroundValid ? 'field' : 'field field--invalid'}><label htmlFor="booking-turnaround">Turnaround (minutes)</label>
            <input id="booking-turnaround" type="number" min="0" max="10080" step="1" required value={turnaround}
              aria-invalid={!turnaroundValid} aria-describedby={!turnaroundValid ? 'booking-turnaround-error' : undefined}
              onChange={e => setTurnaround(e.target.value)} />
            {!turnaroundValid && <span id="booking-turnaround-error" className="field-error" role="alert">Enter whole minutes from 0 to 10,080.</span>}
          </div>
          {preview && <p>Proposed occupied time (SGT): {range(preview.effectiveStart, preview.effectiveEnd)}</p>}
          <TextField id="timing-reason" label="Reason for booking timings" multiline value={note}
            placeholder="e.g. Allow extra setup time for stage equipment and teardown after the event."
            error={noteError} onChange={value => { setNote(value); setNoteError('') }} />
          <p>New conflicts are blocked. Reducing an existing occupied period is allowed so conflicts can be resolved.</p>
          <Button type="submit" disabled={!valid}>{busy ? 'Saving…' : 'Save booking timings'}</Button>
        </fieldset>
      </form>
    </details>}
    {success && <p role="status">{success}</p>}
    {error && <Notice tone="danger" as="div"><p>{error.message}</p>
      {error instanceof ApiClientError && <ul>
        {error.conflicts.map(c => <li key={c.bookingId}>{c.eventName} — occupied {range(c.effectiveStart ?? c.startDatetime, c.effectiveEnd ?? c.endDatetime)}</li>)}
        {error.unavailablePeriods.map(p => <li key={p.unavailabilityId}>{p.reason} — {range(p.startDatetime, p.endDatetime)}</li>)}
      </ul>}
    </Notice>}
  </Card>
}
