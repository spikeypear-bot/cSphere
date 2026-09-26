import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { RequestByline } from '../../components/ui/RequestByline'
import { CheckboxField, DateTimeField, NumberField, TextField } from '../../components/ui/fields'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ActivityDto } from '../../types/activity'
import {
  ACCESSIBILITY_LABELS,
  FIELD_LABELS,
  type AccessibilityFeature,
  type EventRequestDto,
  type SaveEventRequestRequest,
} from '../../types/eventRequest'
import { dateRangeError } from './eventRequestValidation'
import './ClarificationResponsePage.css'

const MAX_RESPONSE = 2000
const ACCESSIBILITY_OPTIONS = Object.keys(ACCESSIBILITY_LABELS) as AccessibilityFeature[]
const FIELD_ORDER = [
  'eventName', 'purpose', 'description', 'startDatetime', 'endDatetime', 'expectedAttendance',
  'venueRequirements', 'accessibilityNeeds', 'equipmentRequirements', 'registrationNeeds',
] as const

type Fields = Required<SaveEventRequestRequest>

function fieldsFrom(request: EventRequestDto): Fields {
  return {
    eventName: request.eventName,
    purpose: request.purpose,
    description: request.description,
    startDatetime: request.startDatetime,
    endDatetime: request.endDatetime,
    expectedAttendance: request.expectedAttendance,
    venueRequirements: request.venueRequirements,
    equipmentRequirements: request.equipmentRequirements,
    accessibilityNeeds: request.accessibilityNeeds,
    registrationNeeds: request.registrationNeeds,
  }
}

/**
 * EO26: answer a coordinator's clarification request. The question and the
 * fields it flags come first; those fields are listed first and highlighted
 * in the form, while everything else stays editable. Saving keeps the request
 * with the organiser; "Save and resubmit" sends it back to the same
 * coordinator with a short reply, after the same checks as the original
 * submission. The whole conversation stays visible alongside.
 */
export function ClarificationResponsePage() {
  const { requestId } = useParams<{ requestId: string }>()
  const navigate = useNavigate()
  const [request, setRequest] = useState<EventRequestDto | null>(null)
  const [timeline, setTimeline] = useState<ActivityDto[]>([])
  const [fields, setFieldsState] = useState<Fields | null>(null)
  const [response, setResponse] = useState('')
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'save' | 'resubmit' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [missing, setMissing] = useState<string[]>([])
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (!requestId) return
    let cancelled = false
    Promise.all([
      apiClient.get<EventRequestDto>(`/event-requests/${requestId}`),
      apiClient.get<ActivityDto[]>(`/event-requests/${requestId}/timeline`),
    ]).then(([r, t]) => {
      if (cancelled) return
      setRequest(r)
      setFieldsState(fieldsFrom(r))
      setTimeline(t)
    }).catch((err: unknown) => {
      if (!cancelled) setLoadError(err instanceof ApiClientError ? err.message : 'Could not load this request.')
    })
    return () => {
      cancelled = true
    }
  }, [requestId])

  if (loadError) {
    return <div className="clarify"><Link to="/organiser">Back to your requests</Link><p role="alert">{loadError}</p></div>
  }
  if (!request || !fields) return <p>Loading…</p>

  if (request.status !== 'clarification_required') {
    return (
      <div className="clarify">
        <Link to="/organiser">Back to your requests</Link>
        <p role="status">This request is not waiting for clarification any more.</p>
      </div>
    )
  }

  const question = [...timeline].reverse().find((e) => e.type === 'clarification_requested')
  const flagged = question?.flaggedFields ?? []
  const ordered = [...FIELD_ORDER].sort((a, b) => Number(flagged.includes(b)) - Number(flagged.includes(a)))
  const rangeError = dateRangeError(fields.startDatetime, fields.endDatetime)
  const responseLength = response.trim().length

  function setFields(patch: Partial<Fields>) {
    setFieldsState((prev) => (prev ? { ...prev, ...patch } : prev))
    setSaved(false)
  }

  function errorFor(field: string) {
    if ((field === 'startDatetime' || field === 'endDatetime') && rangeError) return rangeError
    return missing.includes(field) ? `${FIELD_LABELS[field]} is required` : undefined
  }

  async function save() {
    await apiClient.put<EventRequestDto>(`/event-requests/${requestId}`, fields)
  }

  async function handleSave() {
    setBusy('save')
    setError(null)
    try {
      await save()
      setSaved(true)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Your changes could not be saved.')
    } finally {
      setBusy(null)
    }
  }

  async function handleResubmit() {
    setBusy('resubmit')
    setError(null)
    setMissing([])
    try {
      await save()
      await apiClient.post<EventRequestDto>(`/event-requests/${requestId}/resubmit`, { response })
      navigate('/organiser', { state: { justResubmitted: true } })
    } catch (err) {
      if (err instanceof ApiClientError && err.missingFields) setMissing(err.missingFields)
      setError(err instanceof ApiClientError ? err.message : 'The request could not be resubmitted.')
    } finally {
      setBusy(null)
    }
  }

  function renderField(field: (typeof FIELD_ORDER)[number]): ReactNode {
    const id = `clarify-${field}`
    const label = FIELD_LABELS[field]
    switch (field) {
      case 'startDatetime':
      case 'endDatetime':
        return <DateTimeField id={id} label={label} value={fields![field]} error={errorFor(field)}
          onChange={(v) => setFields({ [field]: v })} />
      case 'expectedAttendance':
        return <NumberField id={id} label={label} min={1} value={fields!.expectedAttendance} error={errorFor(field)}
          onChange={(v) => setFields({ expectedAttendance: v })} />
      case 'accessibilityNeeds':
        return <ChipGroup label={label} options={ACCESSIBILITY_OPTIONS} labels={ACCESSIBILITY_LABELS}
          selected={fields!.accessibilityNeeds} error={errorFor(field)}
          onChange={(selected) => setFields({
            accessibilityNeeds: selected.includes('none') && !fields!.accessibilityNeeds.includes('none')
              ? ['none'] : selected.filter((o) => o !== 'none'),
          })} />
      case 'registrationNeeds':
        return <CheckboxField id={id} label="Attendees need to register" checked={Boolean(fields!.registrationNeeds)}
          onChange={(v) => setFields({ registrationNeeds: v })} />
      default:
        return <TextField id={id} label={label} value={fields![field] ?? ''} error={errorFor(field)}
          multiline={field !== 'eventName'} onChange={(v) => setFields({ [field]: v })} />
    }
  }

  return (
    <div className="clarify">
      <Link to="/organiser">Back to your requests</Link>
      <div>
        <h1>{request.eventName || 'Your event request'}</h1>
        <RequestByline createdAt={request.createdAt} updatedAt={request.updatedAt} timeline={timeline}
          createdByName={request.createdByName} />
      </div>

      <div className="clarify__layout">
        <div className="clarify__main">
          {question ? (
            <Card className="clarify__question">
              <p className="clarify__who">
                <strong>{question.actorName}</strong> (your Event Coordinator) asked {formatRelativeTime(question.occurredAt)}:
              </p>
              <blockquote>{question.message}</blockquote>
              {flagged.length > 0 ? (
                <p className="field-hint">About: {flagged.map((f) => FIELD_LABELS[f] ?? f).join(', ')}</p>
              ) : null}
            </Card>
          ) : null}

          <Card className="clarify__form">
            <h2>Update your request</h2>
            <p className="field-hint">Highlighted fields are the ones your coordinator asked about. Everything else
              you submitted is kept as it is.</p>
            {ordered.map((field) => (
              <div key={field} className="clarify__field" data-flagged={flagged.includes(field)}>
                {flagged.includes(field) ? <span className="clarify__tag">Requested by your coordinator</span> : null}
                {renderField(field)}
              </div>
            ))}
            <div className="clarify__save">
              <Button variant="secondary" disabled={busy !== null} onClick={() => void handleSave()}>
                {busy === 'save' ? 'Saving…' : 'Save changes'}
              </Button>
              {saved ? <span className="field-hint" role="status">Saved. Your request is still with you until you resubmit.</span> : null}
            </div>
          </Card>

          <Card className="clarify__reply">
            <label htmlFor="clarify-response"><strong>Your reply to the coordinator</strong></label>
            <textarea id="clarify-response" rows={4} value={response} onChange={(e) => setResponse(e.target.value)}
              placeholder="e.g. Confirmed 120 attendees and moved the end time to 5pm." />
            <span className={responseLength > MAX_RESPONSE ? 'field-error' : 'field-hint'}>{responseLength}/{MAX_RESPONSE}</span>
            {error ? <p role="alert" className="clarify__error">{error}</p> : null}
            <Button disabled={busy !== null || responseLength === 0 || responseLength > MAX_RESPONSE || Boolean(rangeError)}
              onClick={() => void handleResubmit()}>
              {busy === 'resubmit' ? 'Resubmitting…' : 'Save and resubmit'}
            </Button>
          </Card>
        </div>

        <aside className="clarify__history" aria-labelledby="clarify-history-heading">
          <h2 id="clarify-history-heading">Conversation &amp; history</h2>
          <ActivityTimeline entries={timeline} />
        </aside>
      </div>
    </div>
  )
}
