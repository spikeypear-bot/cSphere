import { useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Notice } from '../../components/ui/Notice'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueHold } from '../../types/venueHold'
import type { VenueBookingDto, VenueBookingConflict, VenueUnavailabilityConflict } from '../../types/venueBooking'
import { useVenueRead } from './useVenueRead'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'
import { formatEventDateTime, formatEventTimeRange } from './formatEventDateTime'
import { BookingDecisionPanel } from './BookingDecisionPanel'
import { BookingConflictCard, UnavailabilityConflictCard } from './BookingConflictCard'

interface HoldPanelProps {
  bookingId: string
  pending: boolean
  booking?: VenueBookingDto
  onChanged: (booking?: VenueBookingDto) => void | Promise<void>
  initialHolds?: VenueHold[]
  creationOpen?: boolean
  onCancel?: () => void
  onCreated?: () => void
  onConflicts?: (bookings: VenueBookingConflict[], periods: VenueUnavailabilityConflict[]) => void
}
type HoldAction = { kind: 'create' } | { kind: 'release' | 'convert'; hold: VenueHold }
export function VenueHoldPanel(props: HoldPanelProps) {
  return props.initialHolds ? <HoldContent {...props} holds={props.initialHolds} /> : <LoadedHoldPanel {...props} />
}
function LoadedHoldPanel(props: HoldPanelProps) {
  const read = useVenueRead<VenueHold[]>(`/venue-bookings/${props.bookingId}/holds`, true, 5000)
  if (read.error) return <section aria-label="Tentative venue hold"><Notice tone="danger">{read.error}</Notice><Button onClick={read.retry}>Retry hold details</Button></section>
  if (!read.data) return <p role="status">Loading hold details…</p>
  return <HoldContent {...props} holds={Array.isArray(read.data) ? read.data : NO_HOLDS}
    onChanged={async updated => { read.retry(); await props.onChanged(updated) }} />
}
function HoldContent({ bookingId, pending, booking, onChanged, holds, creationOpen = true, onCancel, onCreated, onConflicts }: HoldPanelProps & { holds: VenueHold[] }) {
  const [saved, setSaved] = useState<{ source: VenueHold[]; holds: VenueHold[]; approved: boolean } | null>(null)
  const currentHolds = saved?.source === holds ? saved.holds : holds
  const currentPending = pending && !(saved?.source === holds && saved.approved)
  const expired = useHoldDeadline(currentHolds, () => { void onChanged() })
  const active = currentHolds.find(h => h.status === 'active' && !expired.has(h.holdId))
  const [expiry, setExpiry] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [conflicts, setConflicts] = useState<VenueBookingConflict[]>([])
  const [periods, setPeriods] = useState<VenueUnavailabilityConflict[]>([])
  const request = booking ? { ...booking.event,
    startDatetime: booking.timing?.effectiveStart ?? booking.event.startDatetime,
    endDatetime: booking.timing?.effectiveEnd ?? booking.event.endDatetime } : undefined
  async function refresh(updated?: VenueBookingDto) {
    try { await onChanged(updated) }
    catch { setError('The hold action completed, but booking details could not be refreshed. Try again before making another decision.') }
  }
  async function act(action: HoldAction) {
    if (busy) return
    const instant = `${expiry.length === 16 ? `${expiry}:00` : expiry}+08:00`
    if (action.kind === 'create' && (!expiry || !Number.isFinite(Date.parse(instant)) || Date.parse(instant) <= Date.now())) {
      setError('Choose an expiry later than the current time (Singapore time).'); return
    }
    setBusy(true); setError(''); setSuccess(''); setConflicts([]); setPeriods([]); onConflicts?.([], [])
    try {
      let updated: VenueBookingDto | undefined
      if (action.kind === 'create') {
        const created = await apiClient.post<VenueHold>(`/venue-bookings/${bookingId}/holds`, { expiresAt: instant })
        const next = [created, ...currentHolds]
        setSaved({ source: holds, holds: next, approved: false })
        if (booking) updated = { ...booking, holds: next }
        setExpiry(''); onCreated?.()
      } else if (action.kind === 'release') {
        const released = await apiClient.patch<VenueHold>(`/venue-holds/${action.hold.holdId}/release`)
        const next = currentHolds.map(h => h.holdId === action.hold.holdId ? released : h)
        setSaved({ source: holds, holds: next, approved: false })
        if (booking) updated = { ...booking, holds: next }
      } else {
        updated = await apiClient.patch<VenueBookingDto>(`/venue-holds/${action.hold.holdId}/convert`)
        setSaved({ source: holds, holds: updated.holds ?? currentHolds.map(h => h.holdId === action.hold.holdId ? { ...h, status: 'converted' } : h), approved: true })
      }
      setSuccess(action.kind === 'create' ? 'Tentative hold created.' : action.kind === 'release' ? 'Hold released.' : 'Hold converted into an approved booking.')
      await refresh(updated)
    } catch (cause) {
      setError(cause instanceof ApiClientError ? cause.message : 'Could not update the hold. Check the latest booking details before retrying.')
      if (cause instanceof ApiClientError) {
        setConflicts(cause.conflicts); setPeriods(cause.unavailablePeriods)
        onConflicts?.(cause.conflicts, cause.unavailablePeriods)
      }
      // A lost response may follow a committed mutation; read the actual state.
      try { await onChanged() } catch { setError(previous => previous + ' Latest booking details could not be retrieved.') }
    } finally { setBusy(false) }
  }
  const showConfirmation = currentPending && (!!active || creationOpen)
  if (!showConfirmation && currentHolds.length === 0) return null
  return <section className="venue-hold-panel" aria-label="Tentative venue hold" aria-busy={busy}>
    <h2>{showConfirmation ? 'Tentative Venue Hold' : 'Venue hold history'}</h2>
    {showConfirmation && <BookingDecisionPanel>
      <p>A hold temporarily reserves the full occupied period, including setup and turnaround, until its expiry.</p>
      {booking?.timing && <dl className="venue-hold-panel__summary"><div><dt>Venue Occupied (SGT)</dt>
        <dd>{formatEventTimeRange(booking.timing.effectiveStart, booking.timing.effectiveEnd)}</dd></div></dl>}
      {error && <Notice tone="danger" as="div"><p>{error}</p>
        {request && conflicts.map(conflict => <BookingConflictCard key={conflict.bookingId} conflict={conflict} request={request} />)}
        {request && periods.map(period => <UnavailabilityConflictCard key={period.unavailabilityId} period={period} request={request} />)}
      </Notice>}
      {success && <Notice>{success}</Notice>}
      {currentPending && !active && creationOpen && <form className="venue-hold-panel__form" onSubmit={event => { event.preventDefault(); void act({ kind: 'create' }) }}>
        <div className="field">
          <label htmlFor={`hold-expiry-${bookingId}`}>Hold expiry (Singapore time)</label>
          <input id={`hold-expiry-${bookingId}`} type="datetime-local" required value={expiry}
            disabled={busy} aria-invalid={!!error} onChange={event => setExpiry(event.target.value)} />
          <p className="field-hint">Choose the deadline for finalising this reservation. Times are in Singapore time (UTC+08:00).</p>
        </div>
        <div className="booking-approval__actions venue-hold-panel__actions">
          <Button type="submit" disabled={busy}>Place tentative hold</Button>
          {onCancel && <Button type="button" variant="secondary" disabled={busy} onClick={() => { setError(''); setSuccess(''); setExpiry(''); setConflicts([]); setPeriods([]); onCancel() }}>Cancel</Button>}
        </div>
      </form>}
      {active && currentPending && <div className="booking-approval__actions venue-hold-panel__actions">
        <Button variant="secondary" disabled={busy} onClick={() => void act({ kind: 'release', hold: active })}>Release Hold</Button>
        <Button disabled={busy} onClick={() => void act({ kind: 'convert', hold: active })}>Convert Hold to Approved Booking</Button>
      </div>}
    </BookingDecisionPanel>}
    {!showConfirmation && success && <Notice>{success}</Notice>}
      {currentHolds.length > 0 && <ul className="venue-hold-panel__history" aria-label="Hold history">{currentHolds.map(h => <li key={h.holdId}>
        <Card className="venue-card-preview venue-hold-panel__history-card">
        <strong className="venue-card-preview__tag booking-status-tag" data-booking-status={h.status === 'active' && !expired.has(h.holdId) ? 'tentative' : h.status === 'converted' ? 'approved' : undefined}>
          {expired.has(h.holdId) && h.status === 'active' ? 'Expired' : h.status[0].toUpperCase() + h.status.slice(1)}
        </strong>
        <dl className="venue-card-preview__information venue-hold-panel__history-information">
          <div><dt>Occupied period (SGT)</dt><dd>{formatEventTimeRange(h.reservedStart, h.reservedEnd)}</dd></div>
          <div><dt>Expires (SGT)</dt><dd>{formatEventDateTime(h.expiresAt)}</dd></div>
          <div><dt>Created (SGT)</dt><dd>{formatEventDateTime(h.createdAt)}</dd></div>
        </dl>
        </Card>
      </li>)}</ul>}
  </section>
}
