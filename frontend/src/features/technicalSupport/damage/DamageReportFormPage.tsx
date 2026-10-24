import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { PageHeader } from '../../../components/ui/PageHeader'
import { ApiClientError } from '../../../lib/apiClient'
import { EquipmentImageCredits } from '../../equipment/EquipmentImageCredits'
import { EquipmentPhoto } from './EquipmentPhoto'
import { fetchCatalogue, fetchUnits, reportDamage } from './equipmentDamageApi'
import { DESCRIPTION_MAX, type CatalogueItem, type EquipmentUnitStatus } from './equipmentDamage.types'
import './equipmentDamage.css'

const DAY_MS = 24 * 60 * 60 * 1000

// "2027-03-01T09:00" (local) -> exact moment.
function toIso(local: string) {
  return new Date(local).toISOString()
}

/** TS09 AC1–AC4: report a damaged serialised unit or a damaged quantity. */
export function DamageReportFormPage() {
  const navigate = useNavigate()
  const [catalogue, setCatalogue] = useState<CatalogueItem[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [equipmentId, setEquipmentId] = useState('')
  const [serialNumber, setSerialNumber] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [description, setDescription] = useState('')
  const [periodStart, setPeriodStart] = useState('')
  const [periodEnd, setPeriodEnd] = useState('')
  const [markFaulty, setMarkFaulty] = useState(false)
  const [units, setUnits] = useState<EquipmentUnitStatus[] | null>(null)
  const [unitsError, setUnitsError] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [triedSubmit, setTriedSubmit] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetchCatalogue()
      .then((loaded) => {
        if (cancelled) return
        setCatalogue(loaded)
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoadError(err instanceof ApiClientError ? err.message : 'Could not load the equipment catalogue.')
      })
    return () => { cancelled = true }
  }, [attempt])

  const equipment = catalogue?.find((item) => item.equipmentId === equipmentId) ?? null
  const serialised = equipment?.serialised ?? false

  // A serialised kind needs a unit; list them once the period is known.
  const startValid = periodStart !== '' && !Number.isNaN(new Date(periodStart).getTime())
  useEffect(() => {
    if (!serialised || !startValid) return
    let cancelled = false
    const start = toIso(periodStart)
    const end = periodEnd && new Date(periodEnd) > new Date(periodStart)
      ? toIso(periodEnd)
      : new Date(new Date(periodStart).getTime() + DAY_MS).toISOString()
    fetchUnits(start, end)
      .then((loaded) => {
        if (cancelled) return
        setUnits(loaded.filter((unit) => unit.equipmentId === equipmentId))
        setUnitsError(false)
      })
      .catch(() => {
        if (!cancelled) setUnitsError(true)
      })
    return () => { cancelled = true }
  }, [serialised, startValid, periodStart, periodEnd, equipmentId])

  const trimmed = description.trim()
  const quantityNumber = Number(quantity)
  const problems: Record<string, string> = {}
  if (!equipment) problems.equipment = 'Choose the damaged equipment.'
  if (serialised && !serialNumber) problems.serialNumber = 'Choose the damaged unit.'
  if (equipment && !serialised && (!Number.isInteger(quantityNumber) || quantityNumber < 1 || quantityNumber > equipment.totalQuantity)) {
    problems.quantity = `Enter a whole number from 1 to ${equipment.totalQuantity}.`
  }
  if (!trimmed) problems.description = 'Describe the damage.'
  else if (trimmed.length > DESCRIPTION_MAX) problems.description = `Keep the description to ${DESCRIPTION_MAX} characters.`
  if (!startValid) problems.periodStart = 'Enter when the damage starts.'
  if (startValid && periodEnd && !(new Date(periodEnd) > new Date(periodStart))) {
    problems.periodEnd = 'The end must be after the start.'
  }
  const valid = Object.keys(problems).length === 0
  const show = (field: string) => (triedSubmit ? problems[field] : undefined)

  function chooseEquipment(id: string) {
    setEquipmentId(id)
    setSerialNumber('')
    setUnits(null)
    setQuantity('1')
    setMarkFaulty(false)
  }

  async function submit() {
    setTriedSubmit(true)
    if (!valid || !equipment) return
    setSubmitting(true)
    setError(null)
    try {
      const report = await reportDamage({
        equipmentId: equipment.equipmentId,
        serialNumber: serialised ? serialNumber : null,
        quantity: serialised ? null : quantityNumber,
        description: trimmed,
        periodStart: toIso(periodStart),
        periodEnd: periodEnd ? toIso(periodEnd) : null,
        markFaulty: serialised && markFaulty,
      })
      navigate(`/technical-support/damaged-equipment/${report.reportId}`)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'The damage report could not be saved. Nothing was changed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="page page--compact">
      <PageHeader
        title="Report damaged equipment"
        description="Affected events are found from their reservations once the report is saved."
        actions={<Link className="button button--secondary" to="/technical-support/damaged-equipment">Back to reports</Link>}
      />
      {loadError ? (
        <div role="alert" className="equipment-damage__error">
          <p>The equipment catalogue could not be loaded. {loadError}</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      ) : !catalogue ? (
        <p role="status">Loading equipment…</p>
      ) : (
        <Card className="equipment-damage__form">
          <form noValidate onSubmit={(e) => { e.preventDefault(); void submit() }}>
            <label className="equipment-damage__field">
              Equipment
              <select value={equipmentId} onChange={(e) => chooseEquipment(e.target.value)} aria-invalid={Boolean(show('equipment'))}>
                <option value="">Choose equipment…</option>
                {catalogue.map((item) => (
                  <option key={item.equipmentId} value={item.equipmentId}>
                    {item.equipmentName} ({item.serialised ? 'tracked by unit' : `${item.totalQuantity} owned`})
                  </option>
                ))}
              </select>
            </label>
            {show('equipment') ? <p className="field-error">{show('equipment')}</p> : null}

            {equipment ? (
              <div className="equipment-damage__chosen">
                <EquipmentPhoto name={equipment.equipmentName} />
                <p>{equipment.equipmentName}: {serialised ? 'choose the damaged unit below.' : 'enter how many units are damaged.'}</p>
              </div>
            ) : null}

            <label className="equipment-damage__field">
              Damage starts
              <input type="datetime-local" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)}
                aria-invalid={Boolean(show('periodStart'))} />
            </label>
            {show('periodStart') ? <p className="field-error">{show('periodStart')}</p> : null}
            <label className="equipment-damage__field">
              Damage ends (optional)
              <input type="datetime-local" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)}
                aria-invalid={Boolean(show('periodEnd'))} />
            </label>
            <p className="field-hint">Leave the end empty if the equipment is out of action until further notice.</p>
            {show('periodEnd') ? <p className="field-error">{show('periodEnd')}</p> : null}

            {equipment && serialised ? (
              <>
                <label className="equipment-damage__field">
                  Damaged unit
                  <select value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)}
                    disabled={!units} aria-invalid={Boolean(show('serialNumber'))}>
                    <option value="">{units ? 'Choose a unit…' : 'Enter when the damage starts first'}</option>
                    {(units ?? []).map((unit) => (
                      <option key={unit.serialNumber} value={unit.serialNumber}>
                        {unit.serialNumber} ({unit.status})
                      </option>
                    ))}
                  </select>
                </label>
                {unitsError ? <p role="alert">The units could not be loaded. Change the dates to try again.</p> : null}
                {show('serialNumber') ? <p className="field-error">{show('serialNumber')}</p> : null}
                <label className="equipment-damage__checkbox">
                  <input type="checkbox" checked={markFaulty} onChange={(e) => setMarkFaulty(e.target.checked)} />
                  Also mark this unit Faulty for the period (applied when the report is closed)
                </label>
              </>
            ) : null}

            {equipment && !serialised ? (
              <>
                <label className="equipment-damage__field">
                  Units damaged
                  <input type="number" min="1" max={equipment.totalQuantity} step="1" value={quantity}
                    onChange={(e) => setQuantity(e.target.value)} aria-invalid={Boolean(show('quantity'))} />
                </label>
                {show('quantity') ? <p className="field-error">{show('quantity')}</p> : null}
              </>
            ) : null}

            <label className="equipment-damage__field">
              Description
              <textarea rows={4} value={description} maxLength={DESCRIPTION_MAX}
                onChange={(e) => setDescription(e.target.value)} aria-invalid={Boolean(show('description'))} />
            </label>
            {show('description') ? <p className="field-error">{show('description')}</p> : null}

            {error ? <p role="alert">{error}</p> : null}
            <Button type="submit" disabled={submitting}>{submitting ? 'Saving…' : 'Save damage report'}</Button>
          </form>
          {equipment ? <EquipmentImageCredits names={[equipment.equipmentName]} /> : null}
        </Card>
      )}
    </section>
  )
}
