import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { EquipmentPicker } from '../../components/equipment/EquipmentPicker'
import { equipmentDraftFrom, equipmentRequirementsFrom } from '../../lib/equipmentRequirements'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { CheckboxField, DateTimeField, NumberField, TextField } from '../../components/ui/fields'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useRefreshingResource } from '../../lib/useRefreshingResource'
import { ACCESSIBILITY_LABELS, REQUIRED_FIELD_LABELS, type AccessibilityFeature, type EventRequestDto } from '../../types/eventRequest'
import type { ChangeRequestView } from '../../types/changeRequest'
import { venueFacilities, venueFacilityLabels, type Facility } from '../../types/venue'
import { getMissingRequiredFields } from '../organiser/eventRequestCompletion'
import type { DraftFields } from '../organiser/useEventRequestDraft'
import { hasProblems, requestFieldProblems } from '../organiser/eventRequestValidation'
import {
  AFFECTS_ARRANGEMENTS, IMPORTANT_FIELDS, detailsOf, differences, singaporeDate, type ChangeDetails,
} from './changeRequestFields'
import './ChangeRequests.css'

const MAX_REASON = 2000
const ACCESSIBILITY_OPTIONS = Object.keys(ACCESSIBILITY_LABELS) as AccessibilityFeature[]
const SAFETY_STAGES = new Set(['AWAITING_SAFETY_REVIEW', 'SAFETY_APPROVED', 'CONFIRMED'])

/**
 * EO03: the organiser asks to change a submitted request or its event. Two
 * steps: edit (starting from the current approved details, checked as they
 * type by the same rules as submitting) and review (what changes, what it may
 * affect, and why). Nothing changes until the coordinator approves (EC-NEW3).
 */
export function ChangeRequestFormPage() {
  const { requestId: requestParam, eventId } = useParams<{ requestId?: string; eventId?: string }>()
  const navigate = useNavigate()
  const [request, setRequest] = useState<EventRequestDto | null>(null)
  const [pending, setPending] = useState<ChangeRequestView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [denied, setDenied] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [initialEquipmentItems, setInitialEquipmentItems] = useState<ChangeDetails['equipmentItems']>()
  const [details, setDetails] = useState<ChangeDetails | null>(null)
  const [step, setStep] = useState<'edit' | 'review'>('edit')
  const [reason, setReason] = useState('')
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({})
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [capacityConfirmed, setCapacityConfirmed] = useState(false)
  const [catalogue, setCatalogue] = useState<{ equipmentId: string; equipmentName: string }[]>([])
  const [catalogueError, setCatalogueError] = useState(false)
  const [catalogueAttempt, setCatalogueAttempt] = useState(0)
  const registrationPath = request?.registrationNeeds && request.eventId ? `/events/${request.eventId}/registrations` : null
  const registrationInfo = useRefreshingResource(() => apiClient.get<{ confirmedCount: number }>(registrationPath!), registrationPath)

  useEffect(() => {
    let active = true
    apiClient.get<{ equipmentId: string; equipmentName: string }[]>('/equipment/catalogue')
      .then(items => { if (active) { setCatalogue(items); setCatalogueError(false) } })
      .catch(() => { if (active) setCatalogueError(true) })
    return () => { active = false }
  }, [catalogueAttempt])

  useEffect(() => {
    let cancelled = false
    async function load() {
      const found = requestParam
        ? await apiClient.get<EventRequestDto>(`/event-requests/${requestParam}`)
        : (await apiClient.get<EventRequestDto[]>('/event-requests')).find((r) => r.eventId === eventId)
      if (!found) throw new ApiClientError('This event could not be found.', 404)
      const history = await apiClient.get<ChangeRequestView[]>(`/change-requests?requestId=${found.requestId}`)
      if (cancelled) return
      setRequest(found)
      const initial = detailsOf(found)
      setInitialEquipmentItems(initial.equipmentItems)
      setDetails(initial)
      setPending(history.find((c) => c.status === 'pending') ?? null)
      setLoadError(null)
    }
    load().catch((err: unknown) => {
      if (cancelled) return
      // EO03 AC1: another organisation's request is refused (the server says
      // "not found" either way, so ids cannot be probed); retrying cannot help.
      setDenied(err instanceof ApiClientError && (err.status === 403 || err.status === 404))
      setLoadError(err instanceof ApiClientError ? err.message : 'Your event could not be loaded.')
    })
    return () => { cancelled = true }
  }, [requestParam, eventId, attempt])

  if (denied) {
    return (
      <div className="page">
        <div role="alert" className="change-request__error">
          <p>You can't request a change to this event. It belongs to another organisation, or it no longer exists.</p>
          <Link className="button button--secondary" to="/organiser/requests">Back to your event requests</Link>
        </div>
      </div>
    )
  }
  if (loadError) {
    return (
      <div className="page">
        <div role="alert" className="change-request__error">
          <p>{loadError}</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      </div>
    )
  }
  if (!request || !details) return <p role="status">Loading your event…</p>

  const changesPath = `/organiser/requests/${request.requestId}/changes`
  if (pending) {
    return (
      <div className="page">
        <PageHeader title="Request a change" description={request.eventName ?? undefined} />
        <Notice tone="info">
          You already asked to change this event on {new Date(pending.requestedAt).toLocaleDateString('en-SG')}, and it
          is waiting for the coordinator's decision. Withdraw it first if you want to ask for something else.
        </Notice>
        <Link className="button button--primary" to={changesPath}>View your change request</Link>
      </div>
    )
  }

  const legacyEquipment = equipmentDraftFrom(request.equipmentRequirements, catalogue)
  const original = detailsOf(request)
  if (!request.equipmentItems?.length && Object.keys(legacyEquipment.selected).length) {
    original.equipmentItems = Object.entries(legacyEquipment.selected).map(([equipmentId, quantity]) => ({ equipmentId, quantity: Number(quantity) }))
  }
  const equipmentSelection = !request.equipmentItems?.length && details.equipmentItems === initialEquipmentItems
    ? legacyEquipment.selected
    : Object.fromEntries((details.equipmentItems ?? []).map(item => [item.equipmentId, String(item.quantity)]))
  const equipmentNotes = equipmentDraftFrom(details.equipmentRequirements, catalogue).notes
  function updateEquipment(selected: Record<string, string>) {
    set({ equipmentItems: Object.entries(selected).map(([equipmentId, quantity]) => ({ equipmentId, quantity: Number(quantity) })),
      equipmentRequirements: equipmentRequirementsFrom(selected, equipmentNotes, catalogue) })
  }
  const problems = requestFieldProblems(details)
  if (registrationInfo.data && registrationInfo.data.confirmedCount > 0
      && (!details.registrationNeeds || (details.registrationCapacity ?? 0) < registrationInfo.data.confirmedCount)) {
    problems.registrationCapacity = `Capacity cannot be lower than the ${registrationInfo.data.confirmedCount} active confirmed registration(s), and registration cannot be disabled while they exist.`
  }
  const missing = getMissingRequiredFields(details as unknown as DraftFields)
  const proposed = { ...details, equipmentItems: Object.entries(equipmentSelection).map(([equipmentId, quantity]) => ({ equipmentId, quantity: Number(quantity) })) }
  const changes = differences(original, proposed)
  const overnight = singaporeDate(details.startDatetime) !== singaporeDate(details.endDatetime)
  const important = changes.some((c) => IMPORTANT_FIELDS.has(c.field))
  const replans = SAFETY_STAGES.has(request.journeyStatus?.code ?? '') && changes.some((c) => AFFECTS_ARRANGEMENTS.has(c.field))
  const reasonLength = reason.trim().length
  const attendanceChanged = details.expectedAttendance !== original.expectedAttendance
  const needsCapacityConfirmation = details.registrationNeeds && attendanceChanged
    && details.registrationCapacity === original.registrationCapacity
  const equipmentInvalid = (proposed.equipmentItems ?? []).some(item => !Number.isInteger(item.quantity) || item.quantity < 1)
  const blocked = hasProblems(problems) || missing.length > 0 || equipmentInvalid || Boolean(needsCapacityConfirmation && !capacityConfirmed)

  function set(patch: Partial<ChangeDetails>) {
    if ('expectedAttendance' in patch || 'registrationCapacity' in patch || 'registrationNeeds' in patch) setCapacityConfirmed(false)
    setDetails((d) => (d ? { ...d, ...patch } : d))
    // A server message is about the values it saw: changing a field clears it.
    setServerErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => !(k in patch))))
  }

  function errorFor(field: string): string | undefined {
    return problems[field as keyof typeof problems] ?? serverErrors[field]
      ?? (missing.includes(field) ? `${REQUIRED_FIELD_LABELS[field]} is required` : undefined)
  }

  function equipmentSummary(items: ChangeDetails['equipmentItems']) {
    return items?.length ? items.map(item => item.quantity + ' × '
      + (catalogue.find(entry => entry.equipmentId === item.equipmentId)?.equipmentName ?? item.equipmentId)).join(', ') : '—'
  }

  async function send() {
    setSubmitting(true)
    setSubmitError(null)
    try {
      await apiClient.post<ChangeRequestView>('/change-requests', { requestId: request!.requestId, reason, details: proposed, capacityConfirmed })
      navigate(changesPath, { state: { justAsked: true } })
    } catch (err) {
      if (err instanceof ApiClientError && (err.fieldErrors || err.missingFields)) {
        setServerErrors({
          ...Object.fromEntries((err.missingFields ?? []).map((f) => [f, `${REQUIRED_FIELD_LABELS[f] ?? f} is required`])),
          ...(err.fieldErrors ?? {}),
        })
        setStep('edit')
      }
      setSubmitError(err instanceof ApiClientError ? err.message : 'Your change request could not be sent. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="page change-request">
      <PageHeader
        eyebrow={<Link to={changesPath}>Change requests for this event</Link>}
        title="Request a change"
        description={`${request.eventName ?? 'Your event'} · nothing changes until your coordinator approves it`}
      />

      {submitError ? <Notice tone="danger">{submitError}</Notice> : null}

      {step === 'edit' ? (
        <Card className="change-request__form">
          <p className="field-hint">These are your event's current details. Change what needs to change.</p>
          <section className="change-request__section" aria-labelledby="change-event-heading">
            <h2 id="change-event-heading">Event details</h2>
            <TextField id="cr-eventName" label="Event name" value={details.eventName ?? ''} error={errorFor('eventName')}
              onChange={(v) => set({ eventName: v })} />
            <TextField id="cr-purpose" label="Purpose" multiline value={details.purpose ?? ''} error={errorFor('purpose')}
              onChange={(v) => set({ purpose: v })} />
            <TextField id="cr-description" label="Description" multiline value={details.description ?? ''}
              onChange={(v) => set({ description: v })} />
          </section>
          <section className="change-request__section" aria-labelledby="change-schedule-heading">
            <h2 id="change-schedule-heading">Schedule and attendance</h2>
            <DateTimeField id="cr-start" label="Start date & time" min={new Date().toISOString()}
              value={details.startDatetime} error={errorFor('startDatetime')} onChange={(v) => set({ startDatetime: v })} />
            <DateTimeField id="cr-end" label="End date & time" min={details.startDatetime ?? undefined}
              value={details.endDatetime} error={errorFor('endDatetime')} onChange={(v) => set({ endDatetime: v })} />
            {overnight && details.startDatetime && details.endDatetime ? (
              <Notice tone="info">
                This event starts and ends on different dates. Venues can only be booked within one day's opening hours,
                so your coordinator may need to arrange it differently.
              </Notice>
            ) : null}
            <NumberField id="cr-attendance" label="Expected attendance" min={1} wholeNumber
              hint="Whole number of people, at least 1." value={details.expectedAttendance}
              error={errorFor('expectedAttendance')} onChange={(v) => set({ expectedAttendance: v })} />
          </section>
          <section className="change-request__section" aria-labelledby="change-arrangements-heading">
            <h2 id="change-arrangements-heading">Venue and equipment requirements</h2>
            <TextField id="cr-venue" label="Venue requirements" multiline value={details.venueRequirements ?? ''}
              error={errorFor('venueRequirements')} onChange={(v) => set({ venueRequirements: v })} />
            {catalogueError && <p role="alert">The equipment catalogue could not be loaded. <Button variant="secondary" onClick={() => setCatalogueAttempt(n => n + 1)}>Retry catalogue</Button></p>}
            {serverErrors.equipmentItems && <p role="alert">{serverErrors.equipmentItems}</p>}
            <EquipmentPicker catalogue={catalogue} selected={equipmentSelection} catalogueAtBottom
              onToggle={(id, checked) => updateEquipment(checked ? { ...equipmentSelection, [id]: '1' }
                : Object.fromEntries(Object.entries(equipmentSelection).filter(([key]) => key !== id)))}
              onQuantityChange={(id, quantity) => updateEquipment({ ...equipmentSelection, [id]: quantity })} />
            <TextField id="cr-equipment-notes" label="Additional equipment notes (optional)" multiline value={equipmentNotes}
              onChange={notes => set({ equipmentRequirements: equipmentRequirementsFrom(equipmentSelection, notes, catalogue) })} />
            <ChipGroup label="Accessibility needs" options={ACCESSIBILITY_OPTIONS} labels={ACCESSIBILITY_LABELS}
              selected={details.accessibilityNeeds ?? []} error={errorFor('accessibilityNeeds')}
              onChange={(selected) => set({ accessibilityNeeds: selected.includes('none') && !(details.accessibilityNeeds ?? []).includes('none')
                ? ['none'] : selected.filter((s) => s !== 'none') })} />
            <fieldset className="wizard__choice-group">
              <legend>Required facilities</legend>
              {venueFacilities.map((facility: Facility) => (
                <label key={facility}>
                  <input type="checkbox" checked={(details.requiredFacilities ?? []).includes(facility)}
                    onChange={(e) => set({ requiredFacilities: e.target.checked
                      ? [...(details.requiredFacilities ?? []), facility]
                      : (details.requiredFacilities ?? []).filter((f) => f !== facility) })} />
                  {venueFacilityLabels[facility]}
                </label>
              ))}
            </fieldset>
          </section>
          <section className="change-request__section" aria-labelledby="change-registration-heading">
            <h2 id="change-registration-heading">Attendee registration</h2>
            <CheckboxField id="cr-registration" label="Attendees need to register" checked={Boolean(details.registrationNeeds)}
              onChange={(checked) => set({ registrationNeeds: checked })} />
            {!details.registrationNeeds && problems.registrationCapacity && <p role="alert">{problems.registrationCapacity}</p>}
            {registrationInfo.error && <p role="alert">The current registration count could not be retrieved. Capacity will be checked by the server. <Button variant="secondary" onClick={registrationInfo.retry}>Retry registration count</Button></p>}
            {details.registrationNeeds && <>
              <NumberField id="cr-registrationCapacity" label="Registration capacity" min={1} wholeNumber
                value={details.registrationCapacity ?? null} error={errorFor('registrationCapacity')}
                onChange={(value) => set({ registrationCapacity: value })} />
              {needsCapacityConfirmation && <CheckboxField id="cr-capacity-confirmed"
                label="I confirm this registration capacity for the changed expected attendance" checked={capacityConfirmed}
                onChange={setCapacityConfirmed} />}
              <DateTimeField id="cr-registrationOpensAt" label="Registration opens"
                value={details.registrationOpensAt ?? null} error={errorFor('registrationOpensAt')}
                onChange={(value) => set({ registrationOpensAt: value })} />
              <DateTimeField id="cr-registrationClosesAt" label="Registration closes"
                value={details.registrationClosesAt ?? null} error={errorFor('registrationClosesAt')}
                onChange={value => set({ registrationClosesAt: value })} />
              <TextField id="cr-attendeeInstructions" label="Attendee instructions" multiline
                value={details.attendeeInstructions ?? ''} onChange={(value) => set({ attendeeInstructions: value || null })} />
            </>}
          </section>

          <div className="change-request__actions">
            <Link className="button button--secondary" to={changesPath}>Cancel</Link>
            <Button disabled={blocked || changes.length === 0} onClick={() => setStep('review')}>Review the change</Button>
          </div>
          {!blocked && changes.length === 0 ? (
            <p className="field-hint" role="status">Change at least one detail to continue.</p>
          ) : null}
        </Card>
      ) : (
        <Card className="change-request__review">
          <h2>What will change</h2>
          <p className="field-hint">Compare the current approved details with your proposal. Your event stays unchanged until approval.</p>
          <div className="change-request__comparison" role="region" aria-label="Current and proposed details" tabIndex={0}>
            <table className="change-request__diff">
              <thead><tr><th scope="col">Detail</th><th scope="col">Now</th><th scope="col">Proposed</th></tr></thead>
              <tbody>
                {changes.map((c) => (
                  <tr key={c.field}>
                    <th scope="row">{c.label}</th>
                    <td>{c.field === 'equipmentItems' ? equipmentSummary(original.equipmentItems) : c.before}</td>
                    <td><strong>{c.field === 'equipmentItems' ? equipmentSummary(proposed.equipmentItems) : c.after}</strong></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {important ? (
            <Notice tone="info">
              Changes to the date, time, attendance, venue or equipment may mean your coordinator has to rearrange the
              venue or equipment already booked.
            </Notice>
          ) : null}
          {replans ? (
            <Notice tone="info">
              If approved, your event goes back to Planning and its arrangements are confirmed again before it goes ahead.
            </Notice>
          ) : null}
          <TextField id="cr-reason" label="Why is this change needed?" multiline value={reason}
            onChange={setReason} error={reasonLength > MAX_REASON ? `Keep it to ${MAX_REASON} characters.` : undefined}
            hint={`${reasonLength} / ${MAX_REASON} characters. Your coordinator sees this.`} />
          <div className="change-request__actions">
            <Button variant="secondary" onClick={() => setStep('edit')}>Back</Button>
            <Button disabled={submitting || reasonLength === 0 || reasonLength > MAX_REASON} onClick={() => void send()}>
              {submitting ? 'Sending…' : 'Send change request'}
            </Button>
          </div>
        </Card>
      )}
    </div>
  )
}
