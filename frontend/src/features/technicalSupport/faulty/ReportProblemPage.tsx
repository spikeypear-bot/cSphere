import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { PageHeader } from '../../../components/ui/PageHeader'
import { ApiClientError } from '../../../lib/apiClient'
import { fetchCatalogue, reportDamage } from '../damage/equipmentDamageApi'
import { DESCRIPTION_MAX, type CatalogueItem, type DamageBlockStatus } from '../damage/equipmentDamage.types'
import '../damage/equipmentDamage.css'
import { fetchEquipmentDetail } from '../inventory/inventoryApi'
import type { EquipmentDetail } from '../inventory/inventory.types'
import { EquipmentSummaryCard } from '../shared/EquipmentSummaryCard'
import { reportPath, TS } from '../shared/faultyEquipment'
import { addStatusPeriod, ApiError, UNIT_COMMITTED_MESSAGE } from '../status/equipmentStatusApi'
import { toIso } from '../reservations/reservationApi'
import type { BlockStatus } from '../status/equipmentStatus.types'

const BLOCK_STATUSES: BlockStatus[] = ['Faulty', 'Unavailable']
const TO_REPORT_BLOCK: Record<BlockStatus, DamageBlockStatus> = { Faulty: 'FAULTY', Unavailable: 'UNAVAILABLE' }

/** One form for any equipment problem (TS03 + TS09, D32).
 * - A serialised unit is first blocked through TS03. When an event holds the
 *   unit then, TS03 refuses (409, UNIT_COMMITTED_MESSAGE) and the problem is
 *   reported through TS09 instead, asking for the same block when the report
 *   closes, so each affected event can be decided first.
 * - A quantity of a non-serialised kind is always a TS09 report (no block). */
export function ReportProblemPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [catalogue, setCatalogue] = useState<CatalogueItem[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  // ?equipment=&serial= prefill the form from an equipment page.
  const [equipmentId, setEquipmentId] = useState(params.get('equipment') ?? '')
  const [serialNumber, setSerialNumber] = useState(params.get('serial') ?? '')
  const [status, setStatus] = useState<BlockStatus>('Faulty')
  const [quantity, setQuantity] = useState('1')
  const [description, setDescription] = useState('')
  const [periodStart, setPeriodStart] = useState('')
  const [periodEnd, setPeriodEnd] = useState('')
  const [detail, setDetail] = useState<EquipmentDetail | null>(null)
  const [unitsFailedFor, setUnitsFailedFor] = useState<string | null>(null)
  const [unitsAttempt, setUnitsAttempt] = useState(0)
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

  // A serialised kind needs a unit: its units and their status now.
  useEffect(() => {
    if (!serialised || !equipmentId) return
    let cancelled = false
    fetchEquipmentDetail(equipmentId)
      .then((loaded) => { if (!cancelled) setDetail(loaded) })
      .catch(() => { if (!cancelled) setUnitsFailedFor(`${equipmentId}#${unitsAttempt}`) })
    return () => { cancelled = true }
  }, [serialised, equipmentId, unitsAttempt])
  const units = detail?.equipmentId === equipmentId ? detail.units : null
  const unitsFailed = unitsFailedFor === `${equipmentId}#${unitsAttempt}`

  const startValid = periodStart !== '' && !Number.isNaN(new Date(periodStart).getTime())
  const trimmed = description.trim()
  const quantityNumber = Number(quantity)
  const problems: Record<string, string> = {}
  if (!equipment) problems.equipment = 'Choose the equipment.'
  if (serialised && !serialNumber) problems.serialNumber = 'Choose the unit.'
  if (equipment && !serialised && (!Number.isInteger(quantityNumber) || quantityNumber < 1 || quantityNumber > equipment.totalQuantity)) {
    problems.quantity = `Enter a whole number from 1 to ${equipment.totalQuantity}.`
  }
  if (!trimmed) problems.description = 'Describe the problem.'
  else if (trimmed.length > DESCRIPTION_MAX) problems.description = `Keep the description to ${DESCRIPTION_MAX} characters.`
  if (!startValid) problems.periodStart = 'Enter when the problem starts.'
  if (startValid && periodEnd && !(new Date(periodEnd) > new Date(periodStart))) {
    problems.periodEnd = 'The end must be after the start.'
  }
  const valid = Object.keys(problems).length === 0
  const show = (field: string) => (triedSubmit ? problems[field] : undefined)

  function chooseEquipment(id: string) {
    setEquipmentId(id)
    setSerialNumber('')
    setQuantity('1')
    setStatus('Faulty')
  }

  function toReport(blockStatus: DamageBlockStatus | null) {
    return reportDamage({
      equipmentId,
      serialNumber: serialised ? serialNumber : null,
      quantity: serialised ? null : quantityNumber,
      description: trimmed,
      periodStart: toIso(periodStart),
      periodEnd: periodEnd ? toIso(periodEnd) : null,
      blockStatus,
    })
  }

  async function submit() {
    setTriedSubmit(true)
    if (!valid || !equipment) return
    setSubmitting(true)
    setError(null)
    try {
      if (!serialised) {
        const report = await toReport(null)
        navigate(reportPath(report.reportId))
        return
      }
      try {
        await addStatusPeriod(equipmentId, serialNumber, status, periodStart, periodEnd || null)
      } catch (err) {
        if (!(err instanceof ApiError && err.status === 409 && err.message === UNIT_COMMITTED_MESSAGE)) throw err
        // An event holds the unit then: report it so each event is decided first.
        const report = await toReport(TO_REPORT_BLOCK[status])
        navigate(reportPath(report.reportId), {
          state: {
            notice: `An event holds ${equipment.equipmentName} ${serialNumber} during that time, so a damage report was opened. `
              + `Decide what happens to each affected event; the unit is marked ${status} when the report closes.`,
          },
        })
        return
      }
      navigate(`${TS}/faulty`, {
        state: {
          notice: `${equipment.equipmentName} ${serialNumber} marked ${status} `
            + (periodEnd ? 'for the chosen dates.' : 'until further notice.'),
        },
      })
    } catch (err) {
      setError(err instanceof Error && err.message
        ? `${err.message} Nothing was changed.`
        : 'The problem could not be saved. Nothing was changed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="page page--compact">
      <PageHeader
        title="Report a problem"
        description="Mark a unit Faulty or Unavailable, or report damaged equipment. If an event relies on it, a damage report is opened so each affected event can be decided."
        actions={<Link className="button button--secondary" to={`${TS}/faulty`}>Back to faulty equipment</Link>}
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
              <EquipmentSummaryCard name={equipment.equipmentName}>
                <p>
                  {equipment.equipmentName}: {serialised
                    ? 'choose the unit and its status.'
                    : 'units of this kind are not tracked one by one, so enter how many are affected; this is recorded as a damage report.'}
                </p>
              </EquipmentSummaryCard>
            ) : null}

            {equipment && serialised ? (
              <>
                <label className="equipment-damage__field">
                  Unit
                  <select value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)}
                    disabled={!units} aria-invalid={Boolean(show('serialNumber'))}>
                    <option value="">{units ? 'Choose a unit…' : 'Loading units…'}</option>
                    {(units ?? []).map((unit) => (
                      <option key={unit.serialNumber} value={unit.serialNumber}>
                        {unit.serialNumber} ({unit.status})
                      </option>
                    ))}
                  </select>
                </label>
                {unitsFailed ? (
                  <div role="alert" className="equipment-damage__error">
                    <p>The units could not be loaded.</p>
                    <Button variant="secondary" onClick={() => setUnitsAttempt((n) => n + 1)}>Try again</Button>
                  </div>
                ) : null}
                {show('serialNumber') ? <p className="field-error">{show('serialNumber')}</p> : null}
                <label className="equipment-damage__field">
                  Status
                  <select value={status} onChange={(e) => setStatus(e.target.value as BlockStatus)}>
                    {BLOCK_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                  </select>
                </label>
              </>
            ) : null}

            {equipment && !serialised ? (
              <>
                <label className="equipment-damage__field">
                  Units affected
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

            <label className="equipment-damage__field">
              Starts
              <input type="datetime-local" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)}
                aria-invalid={Boolean(show('periodStart'))} />
            </label>
            {show('periodStart') ? <p className="field-error">{show('periodStart')}</p> : null}
            <label className="equipment-damage__field">
              Ends (optional)
              <input type="datetime-local" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)}
                aria-invalid={Boolean(show('periodEnd'))} />
            </label>
            <p className="field-hint">Times are in Singapore time (SGT). Leave the end empty if it is out of action until further notice.</p>
            {show('periodEnd') ? <p className="field-error">{show('periodEnd')}</p> : null}

            {error ? <p role="alert">{error}</p> : null}
            <Button type="submit" disabled={submitting}>{submitting ? 'Saving…' : 'Report problem'}</Button>
          </form>
        </Card>
      )}
    </section>
  )
}
