import { EquipmentPicker, EquipmentSummary } from '../../components/equipment/EquipmentPicker'
import { equipmentDraftFrom } from '../../lib/equipmentRequirements'
import { useEffect, useState } from 'react'
import { useRefreshingResource } from '../../lib/useRefreshingResource'
import { ChangeRequestPanel } from '../changeRequests/ChangeRequestPanel'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { isNotAssigned } from '../../lib/notAssigned'
import type { EventDto } from '../../types/event'
import { EquipmentChangeReviewsPanel } from './EquipmentChangeReviewsPanel'
import type { EventEquipmentDamage } from '../technicalSupport/damage/equipmentDamage.types'
import './EquipmentRequestPage.css'

interface EquipmentCatalogueItem {
  equipmentId: string
  equipmentName: string
  totalQuantity: number
  serialised: boolean
}

interface EquipmentRequestLine {
  equipmentId: string
  equipmentName: string
  quantity: number
}

interface EquipmentRequestDetails {
  requestId: string
  eventId: string
  status: 'processing' | 'approved' | 'rejected' | 'superseded' | 'attention'
  technicalRequirement: string
  rejectReason: string | null
  // EC-NEW3 (D37): why an approved change flagged it; set only while 'attention'.
  attentionReason?: string | null
  lines: EquipmentRequestLine[]
  // EC-NEW3 (D40): replaces the approved request once Technical Support approve it.
  revision?: boolean
}

// EC07 (D40): how many of a kind the event could have at its time.
interface FreeAtEventTime {
  equipmentId: string
  free: number
}

interface DraftLine {
  equipmentId: string
  quantity: number
}

export function EquipmentRequestPage() {
  const { eventId } = useParams<{ eventId: string }>()
  // TS09 AC19: opened from a damage shortfall, the request is for what is missing.
  const [params] = useSearchParams()
  const shortfallId = params.get('shortfall')
  // TS11 AC12: Technical Support asked for action, so the same exception applies.
  const forChange = params.get('additional') === 'change'
  const [loading, setLoading] = useState(true)
  const live = useRefreshingResource(() => Promise.all([
    apiClient.get<EventDto>(`/events/${eventId}`),
    apiClient.get<EquipmentRequestDetails[]>(`/events/${eventId}/equipment-requests`),
  ]), loading ? null : eventId ?? null)
  const [shortfall, setShortfall] = useState<EventEquipmentDamage | null>(null)
  const [initialEvent, setEvent] = useState<EventDto | null>(null)
  const [catalogue, setCatalogue] = useState<EquipmentCatalogueItem[]>([])
  const [initialRequests, setRequests] = useState<EquipmentRequestDetails[]>([])
  const event = live.data?.[0] ?? initialEvent
  const requests = live.data?.[1] ?? initialRequests
  const [technicalRequirement, setTechnicalRequirement] = useState('')
  const [items, setItems] = useState<DraftLine[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notAssigned, setNotAssigned] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  // EC-NEW3: replaces the active request with a complete new one.
  const [revising, setRevising] = useState(false)
  // EC-NEW3 (D37): clears 'attention' with a note, keeping items and reservations.
  const [suitableNote, setSuitableNote] = useState<string | null>(null)
  // EC07 (D40): free stock at the event's time; null while unknown, so nothing is blocked.
  const [freeStock, setFreeStock] = useState<FreeAtEventTime[] | null>(null)

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    apiClient.get<FreeAtEventTime[]>(`/events/${eventId}/equipment-availability`)
      .then((loaded) => { if (!cancelled) setFreeStock(loaded) })
      .catch(() => { if (!cancelled) setFreeStock(null) })
    return () => { cancelled = true }
  }, [eventId])

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    Promise.all([
      apiClient.get<EventDto>(`/events/${eventId}`),
      apiClient.get<EquipmentCatalogueItem[]>('/equipment/catalogue'),
      apiClient.get<EquipmentRequestDetails[]>(`/events/${eventId}/equipment-requests`),
      shortfallId
        ? apiClient.get<EventEquipmentDamage[]>(`/events/${eventId}/equipment-damage`)
        : Promise.resolve([] as EventEquipmentDamage[]),
    ]).then(([loadedEvent, loadedCatalogue, loadedRequests, loadedDamage]) => {
      if (cancelled) return
      setEvent(loadedEvent)
      setCatalogue(loadedCatalogue)
      setRequests(loadedRequests)
      const missing = loadedDamage.find((item) => item.outcomeId === shortfallId && item.canRequestAdditional) ?? null
      setShortfall(missing)
      if (missing) {
        setItems([{ equipmentId: missing.equipmentId, quantity: missing.shortfall }])
        setTechnicalRequirement(`Replaces ${missing.shortfall} × ${missing.equipmentName} reported damaged.`)
      } else {
        const approved = equipmentDraftFrom(loadedEvent.equipmentRequirements, loadedCatalogue)
        setTechnicalRequirement(approved.notes)
        if (!forChange) setItems(loadedEvent.equipmentItems?.length ? loadedEvent.equipmentItems
          : Object.entries(approved.selected).map(([equipmentId, quantity]) => ({ equipmentId, quantity: Number(quantity) })))
        else setItems([])
      }
    }).catch((err: unknown) => {
      if (!cancelled) {
        if (isNotAssigned(err)) setNotAssigned(true)
        setError(err instanceof ApiClientError ? err.message : 'Could not load equipment request details.')
      }
    }).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [eventId, shortfallId, forChange])

  // An additional request for a shortfall sits beside the approved one; only a
  // request still awaiting review blocks it. A superseded request (EC-NEW3)
  // no longer blocks anything either: the revision already replaced it.
  const additional = shortfall !== null || forChange
  const pendingRevision = requests.find((request) => request.status === 'processing' && request.revision)
  const activeRequest = additional
    ? requests.find((request) => request.status === 'processing')
    // EC-NEW3 (D40): a pending revision is what the event now asks for. Otherwise (D37) a
    // flagged request comes first, so a second one (TS09) can still be resolved.
    : pendingRevision ?? requests.find((request) => request.status === 'attention')
      ?? requests.find((request) => request.status === 'processing')
      ?? requests.find((request) => request.status !== 'rejected' && request.status !== 'superseded')
  // D40: the approved request a pending revision will replace, still holding its reservations.
  const replacedRequest = pendingRevision
    ? requests.find((request) => request.status === 'approved' || request.status === 'attention')
    : undefined
  const hasDetails = technicalRequirement.trim() !== '' || items.length > 0
  const itemsAreValid = items.every((item) => Number.isInteger(item.quantity) && item.quantity > 0)
  // EC07 (D40): items asked for in larger numbers than are free at the event's time.
  const shortages = items.flatMap((item) => {
    const free = freeStock?.find((entry) => entry.equipmentId === item.equipmentId)?.free
    return free !== undefined && item.quantity > free ? [{ ...item, free }] : []
  })
  const nameOf = (equipmentId: string) =>
    catalogue.find((entry) => entry.equipmentId === equipmentId)?.equipmentName ?? equipmentId

  // Shared between the first-submission and EC-NEW3 revision forms, which
  // differ only in their surrounding copy and submit button.
  const itemFields = (
    <>
      <EquipmentPicker selectedOnly catalogue={catalogue}
        selected={Object.fromEntries(items.map(item => [item.equipmentId, String(item.quantity)]))}
        onToggle={(equipmentId, checked) => setItems(current => checked
          ? [...current, { equipmentId, quantity: 1 }] : current.filter(item => item.equipmentId !== equipmentId))}
        onQuantityChange={(equipmentId, quantity) => setItems(current => current.map(item => item.equipmentId === equipmentId
          ? { ...item, quantity: Number(quantity) } : item))} disabled={submitting} />
      <label className="equipment-request__field">
        Technical requirements or notes
        <textarea value={technicalRequirement} maxLength={2000} rows={4}
          onChange={change => setTechnicalRequirement(change.target.value)} aria-describedby="equipment-request-hint" />
      </label>
      <p id="equipment-request-hint" className="field-hint">
        Add a note or select at least one item. Event requirements are prefilled when available.
      </p>
      {shortages.length > 0 ? (
        <div role="alert" className="unavailability-warning">
          <p>Not enough equipment is free at the event&rsquo;s time. Lower the quantity before sending:</p>
          <ul>{shortages.map((item) => <li key={item.equipmentId}>
            {nameOf(item.equipmentId)}: {item.quantity} selected, only {item.free} free
          </li>)}</ul>
        </div>
      ) : null}
    </>
  )

  async function submit() {
    if (!eventId || !hasDetails || !itemsAreValid || shortages.length > 0 || activeRequest) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    try {
      const created = await apiClient.post<EquipmentRequestDetails>(`/events/${eventId}/equipment-request`, {
        technicalRequirement: technicalRequirement.trim(),
        items,
        ...(shortfall ? { shortfallOutcomeId: shortfall.outcomeId } : {}),
      })
      live.setData(current => [current?.[0] ?? event!, [...(current?.[1] ?? requests), created]])
      setSuccess(shortfall
        ? 'Additional request for the shortfall submitted to Technical Support for review.'
        : forChange ? 'Additional request submitted to Technical Support for review.'
        : 'Equipment request submitted to Technical Support for review.')
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not submit the equipment request.')
    } finally {
      setSubmitting(false)
    }
  }

  // EC-NEW3: pre-fills the form with the active request's own items so the
  // coordinator edits from what is there now, rather than starting blank.
  function startRevise(request: EquipmentRequestDetails) {
    setTechnicalRequirement(equipmentDraftFrom(request.technicalRequirement, catalogue).notes)
    setItems(event?.equipmentItems?.length ? event.equipmentItems
      : request.lines.map((line) => ({ equipmentId: line.equipmentId, quantity: line.quantity })))
    setError(null)
    setSuccess(null)
    setRevising(true)
  }

  async function markStillSuitable(request: EquipmentRequestDetails) {
    if (!eventId || !suitableNote?.trim()) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    try {
      const updated = await apiClient.patch<EquipmentRequestDetails>(
        `/events/${eventId}/equipment-request/${request.requestId}/still-suitable`, { note: suitableNote.trim() })
      live.setData(current => [current?.[0] ?? event!, (current?.[1] ?? requests).map((r) => r.requestId === updated.requestId ? updated : r)])
      setSuitableNote(null)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not update the equipment request.')
    } finally {
      setSubmitting(false)
    }
  }

  function cancelRevise() {
    setRevising(false)
    setError(null)
  }

  async function reviseRequest() {
    if (!eventId || !hasDetails || !itemsAreValid || shortages.length > 0 || !activeRequest) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    try {
      const revised = await apiClient.patch<EquipmentRequestDetails>(`/events/${eventId}/equipment-request`, {
        technicalRequirement: technicalRequirement.trim(),
        items,
      })
      // D40: only a request still awaiting review is replaced now; an approved one waits for Technical Support.
      live.setData(current => [current?.[0] ?? event!, [
        ...(current?.[1] ?? requests).map((request) => request.status === 'processing'
          ? { ...request, status: 'superseded' as const }
          : request),
        revised,
      ]])
      setRevising(false)
      setSuccess(revised.revision
        ? 'Equipment request revised. Technical Support will review the new request; the current request and '
          + 'its reservations stay until they approve it.'
        : 'Equipment request revised. Technical Support will review the new request.')
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not revise the equipment request.')
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) return <p role="status">Loading equipment request…</p>
  if (notAssigned) return <NotAssignedNotice what="event" />
  if (error && !event) return <p role="alert">{error}</p>
  if (!event) return <p role="alert">Event not found.</p>

  return (
    <section className="page page--compact equipment-request-page">
      <ChangeRequestPanel eventId={eventId} />
      <PageHeader
        title="Request equipment"
        description={`For ${event.eventName}`}
        actions={
          <Link className="button button--secondary" to={`/coordinator/events/${event.eventId}`}>
            Back to event
          </Link>
        }
      />

      {error ? <p role="alert">{error}</p> : null}
      {success ? <Notice>{success}</Notice> : null}
      {live.error && <p role="alert">Could not refresh equipment requests. {live.error}</p>}
      {activeRequest && !revising && (event.equipmentItems?.length ?? 0) > 0 && <Card className="equipment-request__requirements">
        <h2>Approved equipment requirements</h2>
        <ul>{event.equipmentItems!.map(item => <li key={item.equipmentId}>
          {item.quantity} × {catalogue.find(entry => entry.equipmentId === item.equipmentId)?.equipmentName ?? item.equipmentId}
        </li>)}</ul>
      </Card>}
      {shortfallId && !shortfall ? (
        <Notice tone="info">This shortfall can no longer take an additional request.</Notice>
      ) : null}
      {shortfall && !activeRequest ? (
        <Notice tone="info">
          Additional request for the shortfall: {shortfall.shortfall} × {shortfall.equipmentName} reported damaged.
        </Notice>
      ) : null}
      {forChange && !activeRequest ? (
        <Notice tone="info">
          Additional request: Technical Support asked you to act on an approved change. Request only what the event
          now needs on top of its approved equipment.
        </Notice>
      ) : null}

      {requests.filter((request) => request.status === 'rejected').map((request) => (
        <Card key={request.requestId} className="equipment-request__previous">
          <p>
            Previous request was not approved.
            {request.technicalRequirement ? ` Technical note: ${request.technicalRequirement}` : ''}
          </p>
          {request.rejectReason ? <p>Reason: {request.rejectReason}</p> : null}
        </Card>
      ))}

      {revising && activeRequest ? (
        <Card className="equipment-request__form">
          <p>
            Replace the previous request with a complete new one. Technical Support will review it like a
            new request. An approved request keeps its reservations until they approve the new one; if they
            reject it, nothing changes.
          </p>
          {itemFields}
          <Button variant="secondary" onClick={() => {
            setItems(event.equipmentItems ?? [])
            setTechnicalRequirement(equipmentDraftFrom(event.equipmentRequirements, catalogue).notes)
          }}>Use approved equipment requirements</Button>
          <Button onClick={() => void reviseRequest()} disabled={!hasDetails || !itemsAreValid || shortages.length > 0 || submitting}>
            {submitting ? 'Revising…' : 'Submit revision'}
          </Button>
          <Button type="button" variant="ghost" onClick={cancelRevise} disabled={submitting}>
            Cancel
          </Button>
        </Card>
      ) : activeRequest ? (
        <Card className="equipment-request__existing">
          <h2>Request already submitted</h2>
          <p className="equipment-request__status">
            Status: <strong className={`dash-badge dash-badge--${activeRequest.status === 'approved' ? 'success' : 'warning'}`}>
              {activeRequest.status === 'processing' ? 'Awaiting Technical Support review'
              : activeRequest.status === 'attention' ? 'Needs attention' : 'Approved'}</strong>
          </p>
          {activeRequest.status === 'attention' ? (
            <Notice tone="info" as="div">
              <p>{activeRequest.attentionReason}</p>
              <p>
                If a reservation no longer covers the event's new time, revise the request. If only the wording
                changed, you or Technical Support can mark it still suitable, unless they ask you to act.
              </p>
            </Notice>
          ) : null}
          {activeRequest.technicalRequirement ? <p>{activeRequest.technicalRequirement}</p> : null}
          <EquipmentSummary items={activeRequest.lines} />
          {replacedRequest ? (
            <Notice tone="info" as="div">
              <p>
                This revision replaces the approved request once Technical Support approve it. Until then the
                approved request and its reservations stay in place:
              </p>
              <EquipmentSummary items={replacedRequest.lines} />
            </Notice>
          ) : null}
          {!additional ? (
            <Button type="button" variant="secondary" onClick={() => startRevise(activeRequest)}>
              Revise request
            </Button>
          ) : null}
          {activeRequest.status === 'attention' && suitableNote === null ? (
            <Button type="button" variant="ghost" onClick={() => setSuitableNote('')}>
              Mark still suitable
            </Button>
          ) : null}
          {activeRequest.status === 'attention' && suitableNote !== null ? (
            <>
              <label className="equipment-request__field">
                Why is it still suitable?
                <textarea value={suitableNote} maxLength={2000} rows={2}
                  onChange={(change) => setSuitableNote(change.target.value)} />
              </label>
              <Button onClick={() => void markStillSuitable(activeRequest)} disabled={!suitableNote.trim() || submitting}>
                Confirm still suitable
              </Button>
              <Button type="button" variant="ghost" onClick={() => setSuitableNote(null)} disabled={submitting}>
                Cancel
              </Button>
            </>
          ) : null}
        </Card>
      ) : event.status !== 'pending' && !additional ? (
        <Card className="equipment-request__existing">
          <p>Equipment requests can only be submitted while this event is in Planning.</p>
        </Card>
      ) : (
        <Card className="equipment-request__form">
          <p>Technical Support will review the requested items and prepare available resources.</p>
          {itemFields}
          <Button onClick={() => void submit()} disabled={!hasDetails || !itemsAreValid || shortages.length > 0 || submitting}>
            {submitting ? 'Submitting…' : 'Submit equipment request'}
          </Button>
        </Card>
      )}
      {activeRequest ? <EquipmentChangeReviewsPanel eventId={event.eventId} /> : null}
    </section>
  )
}
