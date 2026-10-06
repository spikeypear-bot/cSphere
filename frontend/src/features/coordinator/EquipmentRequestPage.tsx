import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventDto } from '../../types/event'
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
  status: 'processing' | 'approved' | 'rejected'
  technicalRequirement: string
  lines: EquipmentRequestLine[]
}

interface DraftLine {
  equipmentId: string
  quantity: number
}

export function EquipmentRequestPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const [event, setEvent] = useState<EventDto | null>(null)
  const [catalogue, setCatalogue] = useState<EquipmentCatalogueItem[]>([])
  const [requests, setRequests] = useState<EquipmentRequestDetails[]>([])
  const [technicalRequirement, setTechnicalRequirement] = useState('')
  const [items, setItems] = useState<DraftLine[]>([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    Promise.all([
      apiClient.get<EventDto>(`/events/${eventId}`),
      apiClient.get<EquipmentCatalogueItem[]>('/equipment/catalogue'),
      apiClient.get<EquipmentRequestDetails[]>(`/events/${eventId}/equipment-requests`),
    ]).then(([loadedEvent, loadedCatalogue, loadedRequests]) => {
      if (cancelled) return
      setEvent(loadedEvent)
      setCatalogue(loadedCatalogue)
      setRequests(loadedRequests)
      setTechnicalRequirement(loadedEvent.equipmentRequirements ?? '')
    }).catch((err: unknown) => {
      if (!cancelled) {
        setError(err instanceof ApiClientError ? err.message : 'Could not load equipment request details.')
      }
    }).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [eventId])

  const activeRequest = requests.find((request) => request.status !== 'rejected')
  const availableItems = catalogue.filter((equipment) =>
    !items.some((item) => item.equipmentId === equipment.equipmentId))
  const hasDetails = technicalRequirement.trim() !== '' || items.length > 0
  const itemsAreValid = items.every((item) => Number.isInteger(item.quantity) && item.quantity > 0)

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
      })
      setRequests((current) => [...current, created])
      setSuccess('Equipment request submitted to Technical Support for review.')
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not submit the equipment request.')
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) return <p role="status">Loading equipment request…</p>
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

      {requests.filter((request) => request.status === 'rejected').map((request) => (
        <Card key={request.requestId} className="equipment-request__previous">
          <p>
            Previous request was not approved.
            {request.technicalRequirement ? ` Technical note: ${request.technicalRequirement}` : ''}
          </p>
        </Card>
      ))}

      {activeRequest ? (
        <Card className="equipment-request__existing">
          <h2>Request already submitted</h2>
          <p>
            Status: <strong>{activeRequest.status === 'processing' ? 'Awaiting Technical Support review' : 'Approved'}</strong>
          </p>
          {activeRequest.technicalRequirement ? <p>{activeRequest.technicalRequirement}</p> : null}
          {activeRequest.lines.length ? (
            <ul>
              {activeRequest.lines.map((line) => (
                <li key={line.equipmentId}>{line.quantity} × {line.equipmentName}</li>
              ))}
            </ul>
          ) : null}
        </Card>
      ) : event.status !== 'pending' ? (
        <Card className="equipment-request__existing">
          <p>Equipment requests can only be submitted while this event is in Planning.</p>
        </Card>
      ) : (
        <Card className="equipment-request__form">
          <p>Technical Support will review the requested items and prepare available resources.</p>
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
          <Button onClick={() => void submit()} disabled={!hasDetails || !itemsAreValid || submitting}>
            {submitting ? 'Submitting…' : 'Submit equipment request'}
          </Button>
        </Card>
      )}
    </section>
  )
}
