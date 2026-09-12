import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { TextField, NumberField, DateTimeField, CheckboxField } from '../../components/ui/fields'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { StepIndicator, type Step } from '../../components/ui/StepIndicator'
import { AutosaveIndicator } from '../../components/ui/AutosaveIndicator'
import { ACCESSIBILITY_LABELS, REQUIRED_FIELD_LABELS, type AccessibilityFeature } from '../../types/eventRequest'
import { useEventRequestDraft } from './useEventRequestDraft'
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
  const { fields, setFields, status, loading, loadError, autosaveState, save, submit } =
    useEventRequestDraft(requestId)
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

  async function handleSubmit() {
    await save()
    setSubmitting(true)
    try {
      const result = await submit()
      if (result.ok) {
        navigate('/organiser')
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
        <AutosaveIndicator state={autosaveState} />
      </div>

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
            />
            <TextField
              id="purpose"
              label="Purpose"
              value={fields.purpose ?? ''}
              onChange={(value) => setFields({ purpose: value || null })}
              placeholder="Why is this event happening?"
              multiline
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
            />
            <DateTimeField
              id="endDatetime"
              label="End date & time"
              value={fields.endDatetime}
              onChange={(value) => setFields({ endDatetime: value })}
            />
            <NumberField
              id="expectedAttendance"
              label="Expected attendance"
              min={1}
              value={fields.expectedAttendance}
              onChange={(value) => setFields({ expectedAttendance: value })}
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
            />
            <ChipGroup
              label="Accessibility needs"
              options={ACCESSIBILITY_OPTIONS}
              labels={ACCESSIBILITY_LABELS}
              selected={fields.accessibilityNeeds}
              onChange={(selected) => setFields({ accessibilityNeeds: selected })}
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
        <Button
          variant="secondary"
          onClick={() => (stepIndex === 0 ? navigate('/organiser') : goToStep(stepIndex - 1))}
        >
          {stepIndex === 0 ? 'Save & exit' : 'Back'}
        </Button>
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
  return (
    <div className="wizard__review">
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
      </dl>
    </div>
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
