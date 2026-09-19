import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { TextField, NumberField, DateTimeField, CheckboxField } from '../../components/ui/fields'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { StepIndicator, type Step } from '../../components/ui/StepIndicator'
import { AutosaveIndicator } from '../../components/ui/AutosaveIndicator'
import { ACCESSIBILITY_LABELS, REQUIRED_FIELD_LABELS, REQUIRED_FIELD_KEYS, type AccessibilityFeature } from '../../types/eventRequest'
import { useEventRequestDraft, type DraftFields } from './useEventRequestDraft'
import { getMissingRequiredFields, getCompletionPercent } from './eventRequestCompletion'
import './EventRequestWizardPage.css'

const STEPS: Step[] = [
  { key: 'basics', label: 'Basics' },
  { key: 'schedule', label: 'Schedule & attendance' },
  { key: 'venue', label: 'Venue & accessibility' },
  { key: 'equipment', label: 'Equipment & registration' },
  { key: 'review', label: 'Review & submit' },
]

const ACCESSIBILITY_OPTIONS = Object.keys(ACCESSIBILITY_LABELS) as AccessibilityFeature[]

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
    await save()
    setStepIndex(nextIndex)
  }

  // Turns the flat missingFields list (from a blocked submit) into an inline
  // error message on the specific field it belongs to, using the shared
  // field component's built-in error display — previously missingFields was
  // only ever shown as a list on the Review step, never inline on the field
  // itself, so this closes that gap (DEV11 AC17 / DEV11-TC5).
  function fieldError(field: string): string | undefined {
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
    await save()
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
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="wizard">
      <div className="wizard__header">
        <h1>New event request</h1>
        <div className="wizard__header-status">
          <CompletionTracker fields={fields} />
          <AutosaveIndicator state={autosaveState} />
        </div>
      </div>

      {restoredFromLocalBackup ? (
        <p className="wizard__restored-notice" role="status">
          Couldn't reach ConnectSphere when this loaded — showing what you last typed on this
          device. It will sync automatically once you're back online.
        </p>
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
            <TextField
              id="equipmentRequirements"
              label="Equipment requirements"
              hint="Optional — projectors, microphones, hybrid/video-conferencing needs…"
              value={fields.equipmentRequirements ?? ''}
              onChange={(value) => setFields({ equipmentRequirements: value || null })}
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
          <Button onClick={() => goToStep(stepIndex + 1)}>Next</Button>
        ) : (
          <Button onClick={handleSubmit} disabled={submitting}>
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
      <span className="completion-tracker__ring" style={{ ['--percent' as string]: percent }} aria-hidden="true" />
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
