import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { isNotAssigned } from '../../lib/notAssigned'
import type { EventDto } from '../../types/event'
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
}

interface DraftLine {
  equipmentId: string
  quantity: number
}

export function EquipmentRequestPage() {
  const { eventId } = useParams<{ eventId: string }>()
  // TS09 AC19: opened from a damage shortfall, the request is for what is missing.
  const shortfallId = useSearchParams()[0].get('shortfall')
  const [shortfall, setShortfall] = useState<EventEquipmentDamage | null>(null)
  const [event, setEvent] = useState<EventDto | null>(null)
  const [catalogue, setCatalogue] = useState<EquipmentCatalogueItem[]>([])
  const [requests, setRequests] = useState<EquipmentRequestDetails[]>([])
  const [technicalRequirement, setTechnicalRequirement] = useState('')
  const [items, setItems] = useState<DraftLine[]>([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notAssigned, setNotAssigned] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  // EC-NEW3: replaces the active request with a complete new one.
  const [revising, setRevising] = useState(false)
  // EC-NEW3 (D37): clears 'attention' with a note, keeping items and reservations.
  const [suitableNote, setSuitableNote] = useState<string | null>(null)

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
        setTechnicalRequirement(loadedEvent.equipmentRequirements ?? '')
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
  }, [eventId, shortfallId])

  // An additional request for a shortfall sits beside the approved one; only a
  // request still awaiting review blocks it. A superseded request (EC-NEW3)
  // no longer blocks anything either: the revision already replaced it.
  const activeRequest = shortfall
    ? requests.find((request) => request.status === 'processing')
    // EC-NEW3 (D37): a flagged request comes first, so a second one (TS09) can still be resolved.
    : requests.find((request) => request.status === 'attention')
      ?? requests.find((request) => request.status !== 'rejected' && request.status !== 'superseded')
  const availableItems = catalogue.filter((equipment) =>
    !items.some((item) => item.equipmentId === equipment.equipmentId))
  const hasDetails = technicalRequirement.trim() !== '' || items.length > 0
  const itemsAreValid = items.every((item) => Number.isInteger(item.quantity) && item.quantity > 0)

  // Shared between the first-submission and EC-NEW3 revision forms, which
  // differ only in their surrounding copy and submit button.
  const itemFields = (
    <>
      <label className="equipment-request__field">
        Technical requirements or notes
        <textarea
          value={technicalRequirement}
          maxLength={2000}
          rows={4}
          onChange={(eventChange) => setTechnicalRequirement(eventChange.target.value)}
          aria-describedby="equipment-request-hint"
        />
      </label>
      <p id="equipment-request-hint" className="field-hint">
        Add a note or select at least one item. Event requirements are prefilled when available.
      </p>

      <div className="equipment-request__items">
        <div className="equipment-request__items-header">
          <h2>Equipment items</h2>
          <Button type="button" variant="secondary" onClick={addItem} disabled={!availableItems.length}>
            Add item
          </Button>
        </div>
        {items.length === 0 ? <p className="field-hint">No specific catalogue items added.</p> : null}
        {items.map((item, index) => (
          <div className="equipment-request__line" key={`${index}-${item.equipmentId}`}>
            <label>
              Equipment
              <select
                value={item.equipmentId}
                onChange={(eventChange) => updateItem(index, 'equipmentId', eventChange.target.value)}
              >
                {catalogue.filter((equipment) =>
                  equipment.equipmentId === item.equipmentId ||
                  !items.some((other, otherIndex) =>
                    otherIndex !== index && other.equipmentId === equipment.equipmentId))
                  .map((equipment) => (
                    <option key={equipment.equipmentId} value={equipment.equipmentId}>
                      {equipment.equipmentName} ({equipment.totalQuantity} in catalogue)
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Quantity
              <input
                type="number"
                min="1"
                step="1"
                value={item.quantity}
                onChange={(eventChange) => updateItem(index, 'quantity', eventChange.target.value)}
              />
            </label>
            <Button
              type="button"
              variant="ghost"
              aria-label={`Remove ${catalogue.find((equipment) => equipment.equipmentId === item.equipmentId)?.equipmentName ?? 'equipment'} item`}
              onClick={() => setItems((current) => current.filter((_, itemIndex) => itemIndex !== index))}
            >
              Remove
            </Button>
          </div>
        ))}
      </div>
    </>
  )

  function addItem() {
    const firstAvailable = availableItems[0]
    if (!firstAvailable) return
    setItems((current) => [...current, { equipmentId: firstAvailable.equipmentId, quantity: 1 }])
  }

  function updateItem(index: number, field: keyof DraftLine, value: string) {
    setItems((current) => current.map((item, itemIndex) => {
      if (itemIndex !== index) return item
      return field === 'quantity'
        ? { ...item, quantity: Number(value) }
        : { ...item, equipmentId: value }
    }))
  }

  async function submit() {
    if (!eventId || !hasDetails || !itemsAreValid || activeRequest) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    try {
      const created = await apiClient.post<EquipmentRequestDetails>(`/events/${eventId}/equipment-request`, {
        technicalRequirement: technicalRequirement.trim(),
        items,
        ...(shortfall ? { shortfallOutcomeId: shortfall.outcomeId } : {}),
      })
      setRequests((current) => [...current, created])
      setSuccess(shortfall
        ? 'Additional request for the shortfall submitted to Technical Support for review.'
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
    setTechnicalRequirement(request.technicalRequirement ?? '')
    setItems(request.lines.map((line) => ({ equipmentId: line.equipmentId, quantity: line.quantity })))
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
      setRequests((current) => current.map((r) => r.requestId === updated.requestId ? updated : r))
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
    if (!eventId || !hasDetails || !itemsAreValid || !activeRequest) return
    setSubmitting(true)
    setError(null)
    setSuccess(null)
    try {
      const revised = await apiClient.patch<EquipmentRequestDetails>(`/events/${eventId}/equipment-request`, {
        technicalRequirement: technicalRequirement.trim(),
        items,
      })
      setRequests((current) => [
        ...current.map((request) => request.requestId === activeRequest.requestId
          ? { ...request, status: 'superseded' as const }
          : request),
        revised,
      ])
      setRevising(false)
      setSuccess('Equipment request revised. Technical Support will review the new request, '
        + 'and the event’s previous reservations were released.')
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
    <section className="page page--compact">
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
      {shortfallId && !shortfall ? (
        <Notice tone="info">This shortfall can no longer take an additional request.</Notice>
      ) : null}
      {shortfall && !activeRequest ? (
        <Notice tone="info">
          Additional request for the shortfall: {shortfall.shortfall} × {shortfall.equipmentName} reported damaged.
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
            new request, and the event&rsquo;s current equipment reservations will be released.
          </p>
          {itemFields}
          <Button onClick={() => void reviseRequest()} disabled={!hasDetails || !itemsAreValid || submitting}>
            {submitting ? 'Revising…' : 'Submit revision'}
          </Button>
          <Button type="button" variant="ghost" onClick={cancelRevise} disabled={submitting}>
            Cancel
          </Button>
        </Card>
      ) : activeRequest ? (
        <Card className="equipment-request__existing">
          <h2>Request already submitted</h2>
          <p>
            Status: <strong>{activeRequest.status === 'processing' ? 'Awaiting Technical Support review'
              : activeRequest.status === 'attention' ? 'Needs attention' : 'Approved'}</strong>
          </p>
          {activeRequest.status === 'attention' ? (
            <Notice tone="info" as="div">
              <p>{activeRequest.attentionReason}</p>
              <p>
                Mark it still suitable only if nothing about the items needs to change. A new time that a
                reservation no longer covers needs a revision.
              </p>
            </Notice>
          ) : null}
          {activeRequest.technicalRequirement ? <p>{activeRequest.technicalRequirement}</p> : null}
          {activeRequest.lines.length ? (
            <ul>
              {activeRequest.lines.map((line) => (
                <li key={line.equipmentId}>{line.quantity} × {line.equipmentName}</li>
              ))}
            </ul>
          ) : null}
          {!shortfall ? (
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
      ) : event.status !== 'pending' && !shortfall ? (
        <Card className="equipment-request__existing">
          <p>Equipment requests can only be submitted while this event is in Planning.</p>
        </Card>
      ) : (
        <Card className="equipment-request__form">
          <p>Technical Support will review the requested items and prepare available resources.</p>
          {itemFields}
          <Button onClick={() => void submit()} disabled={!hasDetails || !itemsAreValid || submitting}>
            {submitting ? 'Submitting…' : 'Submit equipment request'}
          </Button>
        </Card>
      )}
    </section>
  )
}
