import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { TextField, NumberField, DateTimeField, CheckboxField } from '../../components/ui/fields'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { StepIndicator, type Step } from '../../components/ui/StepIndicator'
import { AutosaveIndicator } from '../../components/ui/AutosaveIndicator'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { ACCESSIBILITY_LABELS, REQUIRED_FIELD_LABELS, REQUIRED_FIELD_KEYS, type AccessibilityFeature } from '../../types/eventRequest'
import { venueFacilities, venueFacilityLabels, type Facility } from '../../types/venue'
import { useEventRequestDraft, type DraftFields } from './useEventRequestDraft'
import { getMissingRequiredFields, getCompletionPercent } from './eventRequestCompletion'
import { dateRangeError } from './eventRequestValidation'
import './EventRequestWizardPage.css'

const STEPS: Step[] = [
  { key: 'basics', label: 'Basics' },
  { key: 'schedule', label: 'Schedule & attendance' },
  { key: 'venue', label: 'Venue & accessibility' },
  { key: 'equipment', label: 'Equipment & registration' },
  { key: 'review', label: 'Review & submit' },
]

const ACCESSIBILITY_OPTIONS = Object.keys(ACCESSIBILITY_LABELS) as AccessibilityFeature[]

interface EquipmentCatalogueItem {
  equipmentId: string
  equipmentName: string
  totalQuantity: number
  serialised: boolean
}

const EQUIPMENT_ITEMS_HEADING = 'Equipment items:'
const EQUIPMENT_NOTES_HEADING = 'Additional notes:'

function equipmentRequirementsFrom(
  selected: Record<string, string>,
  notes: string,
  catalogue: EquipmentCatalogueItem[],
): string | null {
  const lines = catalogue.flatMap((equipment) => {
    const quantity = selected[equipment.equipmentId]
    return quantity === undefined ? [] : [`${quantity} × ${equipment.equipmentName}`]
  })
  const sections = [
    lines.length ? `${EQUIPMENT_ITEMS_HEADING}\n${lines.join('\n')}` : '',
    notes.trim() ? `${EQUIPMENT_NOTES_HEADING}\n${notes.trim()}` : '',
  ].filter(Boolean)
  return sections.length ? sections.join('\n\n') : null
}

function equipmentDraftFrom(
  value: string | null,
  catalogue: EquipmentCatalogueItem[],
): { selected: Record<string, string>; notes: string } {
  if (!value?.startsWith(`${EQUIPMENT_ITEMS_HEADING}\n`)) {
    return { selected: {}, notes: value ?? '' }
  }

  const [itemsSection, ...notesSections] = value.split(`\n\n${EQUIPMENT_NOTES_HEADING}\n`)
  const selected: Record<string, string> = {}
  const lines = itemsSection.slice(EQUIPMENT_ITEMS_HEADING.length).trim().split('\n').filter(Boolean)
  for (const line of lines) {
    const match = /^(\d+)\s+×\s+(.+)$/.exec(line)
    const equipment = match && catalogue.find((item) => item.equipmentName === match[2])
    if (match && equipment) selected[equipment.equipmentId] = match[1]
    else return { selected: {}, notes: value }
  }
  return { selected, notes: notesSections.join(`\n\n${EQUIPMENT_NOTES_HEADING}\n`) }
}

export function EventRequestWizardPage() {
  const { requestId } = useParams()
  const navigate = useNavigate()
  const {
    fields,
    setFields,
    status,
    loading,
    loadError,
    restoredFromLocalBackup,
    autosaveState,
    save,
    submit,
  } = useEventRequestDraft(requestId)
  const [stepIndex, setStepIndex] = useState(0)
  const [missingFields, setMissingFields] = useState<string[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string>()
  const [equipmentCatalogue, setEquipmentCatalogue] = useState<EquipmentCatalogueItem[] | null>(null)
  const [catalogueError, setCatalogueError] = useState<string | null>(null)
  const [selectedEquipment, setSelectedEquipment] = useState<Record<string, string>>({})
  const [equipmentNotes, setEquipmentNotes] = useState('')
  const equipmentDraftInitialized = useRef(false)
  const rangeError = dateRangeError(fields.startDatetime, fields.endDatetime)
  const invalidEquipmentQuantity = Object.values(selectedEquipment).some(
    (quantity) => !/^[1-9]\d*$/.test(quantity),
  )

  useEffect(() => {
    if (stepIndex !== 3 || equipmentCatalogue !== null || catalogueError) return
    let cancelled = false
    apiClient.get<EquipmentCatalogueItem[]>('/equipment/catalogue')
      .then((catalogue) => {
        if (!cancelled) setEquipmentCatalogue(catalogue)
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setCatalogueError(error instanceof ApiClientError ? error.message : 'Could not load equipment options.')
        }
      })
    return () => { cancelled = true }
  }, [stepIndex, equipmentCatalogue, catalogueError])

  useEffect(() => {
    if (!equipmentCatalogue || equipmentDraftInitialized.current) return
    const draft = equipmentDraftFrom(fields.equipmentRequirements, equipmentCatalogue)
    setSelectedEquipment(draft.selected)
    setEquipmentNotes(draft.notes)
    equipmentDraftInitialized.current = true
  }, [equipmentCatalogue, fields.equipmentRequirements])

  if (loading) {
    return <p>Loading your draft…</p>
  }

  if (loadError) {
    return <p role="alert">{loadError}</p>
  }

  if (status && status !== 'draft') {
    // EO01's boundary: once a request has left draft, this wizard isn't the
    // right place for it any more (viewing a submitted request is EO04/EO15,
    // a later slice — see docs/implementation-roadmap.md).
    return (
      <Card style={{ padding: '1.5rem' }}>
        <p>This request has already been submitted and can no longer be edited here.</p>
        <Button variant="secondary" onClick={() => navigate('/organiser')}>
          Back to my event requests
        </Button>
      </Card>
    )
  }

  async function goToStep(nextIndex: number) {
    if (nextIndex > stepIndex && rangeError) return
    if (nextIndex > stepIndex && stepIndex === 3 && invalidEquipmentQuantity) return
    await save()
    setStepIndex(nextIndex)
  }

  function updateEquipmentRequirements(selected: Record<string, string>, notes: string) {
    setFields({
      equipmentRequirements: equipmentRequirementsFrom(selected, notes, equipmentCatalogue ?? []),
    })
  }

  function toggleEquipment(equipmentId: string, checked: boolean) {
    const updated = { ...selectedEquipment }
    if (checked) updated[equipmentId] = '1'
    else delete updated[equipmentId]
    setSelectedEquipment(updated)
    updateEquipmentRequirements(updated, equipmentNotes)
  }

  function updateEquipmentQuantity(equipmentId: string, quantity: string) {
    const updated = { ...selectedEquipment, [equipmentId]: quantity }
    setSelectedEquipment(updated)
    updateEquipmentRequirements(updated, equipmentNotes)
  }

  function updateEquipmentNotes(notes: string) {
    setEquipmentNotes(notes)
    updateEquipmentRequirements(selectedEquipment, notes)
  }

  // Turns the flat missingFields list (from a blocked submit) into an inline
  // error message on the specific field it belongs to, using the shared
  // field component's built-in error display — previously missingFields was
  // only ever shown as a list on the Review step, never inline on the field
  // itself, so this closes that gap (DEV11 AC17 / DEV11-TC5).
  function fieldError(field: string): string | undefined {
    if ((field === 'startDatetime' || field === 'endDatetime') && rangeError) return rangeError
    return missingFields.includes(field) ? `${REQUIRED_FIELD_LABELS[field]} is required` : undefined
  }

  // "No accessibility requirements needed" is a real, exclusive answer — it
  // doesn't make sense selected alongside an actual accommodation, in either
  // direction of the toggle.
  function reconcileAccessibilitySelection(selected: AccessibilityFeature[]): AccessibilityFeature[] {
    const justAddedNone = selected.includes('none') && !fields.accessibilityNeeds.includes('none')
    if (justAddedNone) return ['none']
    return selected.filter((option) => option !== 'none') as AccessibilityFeature[]
  }

  async function handleExit() {
    // "Exit" still saves first — leaving the wizard must never silently
    // discard what was typed (the previous "Save & exit" button looked like
    // it did this but only navigated away, without calling save()).
    await save()
    navigate('/organiser')
  }

  async function handleSubmit() {
    if (rangeError || invalidEquipmentQuantity || submitting) return
    setSubmitError(undefined)
    setSubmitting(true)
    try {
      const result = await submit()
      if (result.ok) {
        // EO02: "is shown that the request has been submitted successfully" —
        // a plain redirect back to the list wasn't itself a confirmation, so
        // the list picks this flag up and shows a banner once.
        navigate('/organiser', { state: { justSubmitted: true } })
      } else {
        setMissingFields(result.missingFields)
      }
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : 'Could not submit. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="page">
      <PageHeader
        title="New event request"
        actions={
          <>
            <CompletionTracker fields={fields} />
            <AutosaveIndicator state={autosaveState} />
          </>
        }
      />

      {restoredFromLocalBackup ? (
        <Notice tone="info">
          Couldn't reach ConnectSphere when this loaded — showing what you last typed on this
          device. It will sync automatically once you're back online.
        </Notice>
      ) : null}

      <StepIndicator steps={STEPS} currentIndex={stepIndex} />

      <Card className="wizard__step">
        {stepIndex === 0 && (
          <>
            <TextField
              id="eventName"
              label="Event name"
              value={fields.eventName ?? ''}
              onChange={(value) => setFields({ eventName: value || null })}
              placeholder="Q1 Partner Town Hall"
              error={fieldError('eventName')}
            />
            <fieldset className="wizard__choice-group">
              <legend>Required facilities</legend>
              <p className="field-hint">Only select facilities the event must have. Venues missing one will be blocked from booking.</p>
              {venueFacilities.map((facility: Facility) => (
                <label key={facility}>
                  <input type="checkbox" checked={(fields.requiredFacilities ?? []).includes(facility)}
                    onChange={(e) => setFields({ requiredFacilities: e.target.checked
                      ? [...(fields.requiredFacilities ?? []), facility]
                      : (fields.requiredFacilities ?? []).filter((value) => value !== facility) })} />
                  {venueFacilityLabels[facility]}
                </label>
              ))}
            </fieldset>
            <TextField
              id="purpose"
              label="Purpose"
              value={fields.purpose ?? ''}
              onChange={(value) => setFields({ purpose: value || null })}
              placeholder="Why is this event happening?"
              multiline
              error={fieldError('purpose')}
            />
            <TextField
              id="description"
              label="Description"
              hint="Optional — extra context for whoever reviews this."
              value={fields.description ?? ''}
              onChange={(value) => setFields({ description: value || null })}
              multiline
            />
          </>
        )}

        {stepIndex === 1 && (
          <>
            <DateTimeField
              id="startDatetime"
              label="Start date & time"
              value={fields.startDatetime}
              onChange={(value) => setFields({ startDatetime: value })}
              error={fieldError('startDatetime')}
            />
            <DateTimeField
              id="endDatetime"
              label="End date & time"
              min={fields.startDatetime ?? undefined}
              value={fields.endDatetime}
              onChange={(value) => setFields({ endDatetime: value })}
              error={fieldError('endDatetime')}
            />
            <NumberField
              id="expectedAttendance"
              label="Expected attendance"
              min={1}
              value={fields.expectedAttendance}
              onChange={(value) => setFields({ expectedAttendance: value })}
              error={fieldError('expectedAttendance')}
            />
          </>
        )}

        {stepIndex === 2 && (
          <>
            <TextField
              id="venueRequirements"
              label="Venue requirements"
              value={fields.venueRequirements ?? ''}
              onChange={(value) => setFields({ venueRequirements: value || null })}
              placeholder="Theatre-style seating for 150, one breakout room…"
              multiline
              error={fieldError('venueRequirements')}
            />
            <ChipGroup
              label="Accessibility needs"
              options={ACCESSIBILITY_OPTIONS}
              labels={ACCESSIBILITY_LABELS}
              selected={fields.accessibilityNeeds}
              onChange={(selected) => setFields({ accessibilityNeeds: reconcileAccessibilitySelection(selected) })}
              error={fieldError('accessibilityNeeds')}
            />
          </>
        )}

        {stepIndex === 3 && (
          <>
            <fieldset className="wizard__choice-group">
              <legend>Equipment requirements</legend>
              <p className="field-hint">Select catalogue items and enter the quantity needed for each.</p>
              {equipmentCatalogue === null && !catalogueError
                ? <p role="status">Loading equipment options…</p>
                : null}
              {catalogueError ? (
                <p role="alert">
                  {catalogueError}{' '}
                  <Button type="button" variant="secondary" onClick={() => setCatalogueError(null)}>
                    Retry
                  </Button>
                </p>
              ) : null}
              {equipmentCatalogue?.length === 0 ? <p>No equipment types are currently listed.</p> : null}
              {equipmentCatalogue?.map((equipment) => {
                const checked = selectedEquipment[equipment.equipmentId] !== undefined
                return (
                  <div className="wizard__equipment-option" key={equipment.equipmentId}>
                    <label>
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(event) => toggleEquipment(equipment.equipmentId, event.target.checked)}
                      />
                      {equipment.equipmentName}
                    </label>
                    {checked ? (
                      <label>
                        Quantity
                        <input
                          type="number"
                          min="1"
                          step="1"
                          value={selectedEquipment[equipment.equipmentId]}
                          onChange={(event) => updateEquipmentQuantity(equipment.equipmentId, event.target.value)}
                          aria-label={`${equipment.equipmentName} quantity`}
                          aria-invalid={!/^[1-9]\d*$/.test(selectedEquipment[equipment.equipmentId])}
                        />
                      </label>
                    ) : null}
                  </div>
                )
              })}
              {invalidEquipmentQuantity ? (
                <p role="alert">Enter a whole quantity greater than zero for each selected item.</p>
              ) : null}
            </fieldset>
            <TextField
              id="equipmentRequirementsNotes"
              label="Additional equipment notes"
              hint="Optional — describe special setup or equipment needs not covered by the catalogue."
              value={equipmentNotes}
              onChange={updateEquipmentNotes}
              multiline
            />
            <CheckboxField
              id="registrationNeeds"
              label="Attendees need to register for this event"
              checked={Boolean(fields.registrationNeeds)}
              onChange={(checked) => setFields({ registrationNeeds: checked })}
            />
          </>
        )}

        {stepIndex === 4 && (
          <ReviewStep
            fields={fields}
            missingFields={missingFields}
            onEditStep={(index) => setStepIndex(index)}
          />
        )}
      </Card>

      {submitError && <p role="alert">{submitError}</p>}
      {rangeError && stepIndex !== 1 && <p role="alert">{rangeError}</p>}
      <div className="wizard__nav">
        {stepIndex === 0 ? (
          <>
            <Button variant="secondary" onClick={() => save()}>
              Save as draft
            </Button>
            <Button variant="secondary" onClick={handleExit}>
              Exit
            </Button>
          </>
        ) : (
          <Button variant="secondary" onClick={() => goToStep(stepIndex - 1)}>
            Back
          </Button>
        )}
        {stepIndex < STEPS.length - 1 ? (
          <Button disabled={!!rangeError || (stepIndex === 3 && invalidEquipmentQuantity)} onClick={() => goToStep(stepIndex + 1)}>Next</Button>
        ) : (
          <Button onClick={handleSubmit} disabled={submitting || !!rangeError || invalidEquipmentQuantity}>
            {submitting ? 'Submitting…' : 'Submit for review'}
          </Button>
        )}
      </div>
    </div>
  )
}

function ReviewStep({
  fields,
  missingFields,
  onEditStep,
}: {
  fields: ReturnType<typeof useEventRequestDraft>['fields']
  missingFields: string[]
  onEditStep: (index: number) => void
}) {
  // Live checklist (EO02 UX enhancement, docs/decision-log.md D17): computed
  // from what's actually in the form right now, so it's visible before ever
  // attempting to submit — `missingFields` (from a blocked submit response)
  // is still shown separately below once it exists, since it's confirmation
  // from the actual source of truth (EventRequestService), not a guess.
  const liveMissing = getMissingRequiredFields(fields)
  return (
    <div className="wizard__review">
      <ChecklistTracker missing={liveMissing} />

      {missingFields.length > 0 ? (
        <div className="wizard__missing" role="alert">
          <p>This request still needs:</p>
          <ul>
            {missingFields.map((field) => (
              <li key={field}>{REQUIRED_FIELD_LABELS[field] ?? field}</li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="field-hint">
          Review everything below, then submit — an Event Coordinator will pick this up next.
        </p>
      )}

      <dl className="wizard__summary">
        <SummaryRow label="Event name" value={fields.eventName} onEdit={() => onEditStep(0)} />
        <SummaryRow label="Purpose" value={fields.purpose} onEdit={() => onEditStep(0)} />
        <SummaryRow
          label="Start"
          value={fields.startDatetime && new Date(fields.startDatetime).toLocaleString()}
          onEdit={() => onEditStep(1)}
        />
        <SummaryRow
          label="End"
          value={fields.endDatetime && new Date(fields.endDatetime).toLocaleString()}
          onEdit={() => onEditStep(1)}
        />
        <SummaryRow
          label="Expected attendance"
          value={fields.expectedAttendance?.toString() ?? null}
          onEdit={() => onEditStep(1)}
        />
        <SummaryRow label="Venue requirements" value={fields.venueRequirements} onEdit={() => onEditStep(2)} />
        <SummaryRow
          label="Required facilities"
          value={(fields.requiredFacilities ?? []).length > 0
            ? (fields.requiredFacilities ?? []).map((facility) => venueFacilityLabels[facility]).join(', ')
            : 'None specified'}
          onEdit={() => onEditStep(2)}
        />
        <SummaryRow
          label="Accessibility needs"
          value={fields.accessibilityNeeds.length > 0 ? fields.accessibilityNeeds.map((need) => ACCESSIBILITY_LABELS[need]).join(', ') : null}
          onEdit={() => onEditStep(2)}
        />
        <SummaryRow label="Equipment requirements" value={fields.equipmentRequirements} onEdit={() => onEditStep(3)} />
        <SummaryRow
          label="Registration needs"
          value={fields.registrationNeeds ? 'Attendees need to register' : 'No registration required'}
          onEdit={() => onEditStep(3)}
        />
      </dl>
    </div>
  )
}

/** Persistent header badge, visible on every step — EO02 UX enhancement
 * (docs/decision-log.md D17): "how close am I" while filling the form in,
 * not only "what did I miss" after clicking Submit. */
function CompletionTracker({ fields }: { fields: DraftFields }) {
  const percent = getCompletionPercent(fields)
  return (
    <span className="completion-tracker" role="status">
      <span className="completion-ring completion-ring--large" style={{ ['--percent' as string]: percent }} aria-hidden="true" />
      {percent}% ready to submit
    </span>
  )
}

/** The Review step's always-visible checklist of every required field, not
 * only the ones a blocked submit reported missing. */
function ChecklistTracker({ missing }: { missing: string[] }) {
  return (
    <ul className="wizard__checklist" aria-label="Required fields checklist">
      {REQUIRED_FIELD_KEYS.map((key) => {
        const done = !missing.includes(key)
        return (
          <li key={key} data-done={done}>
            <span aria-hidden="true">{done ? '✓' : '○'}</span>
            {REQUIRED_FIELD_LABELS[key]}
          </li>
        )
      })}
    </ul>
  )
}

function SummaryRow({
  label,
  value,
  onEdit,
}: {
  label: string
  value: string | null | undefined
  onEdit: () => void
}) {
  return (
    <div className="wizard__summary-row">
      <dt>{label}</dt>
      <dd>{value || <span className="field-hint">Not yet provided</span>}</dd>
      <button type="button" className="button button--ghost" onClick={onEdit}>
        Edit
      </button>
    </div>
  )
}
