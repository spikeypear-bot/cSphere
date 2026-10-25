import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { ApiClientError } from '../../../lib/apiClient'
import { formatEventDateTime, formatEventTimeRange } from '../../venueStaff/formatEventDateTime'
import { EquipmentPhoto } from './EquipmentPhoto'
import { closeReport, decideOutcome, fetchReport, fetchUnits } from './equipmentDamageApi'
import {
  outcomeLabel,
  type AffectedEvent,
  type DamageReport,
  type DecideOutcomeBody,
  type EquipmentUnitStatus,
} from './equipmentDamage.types'
import './equipmentDamage.css'

/** TS09 AC6–AC15: one report, every affected event and what was decided for it. */
export function DamageReportDetailPage() {
  const { reportId } = useParams<{ reportId: string }>()
  const [report, setReport] = useState<DamageReport | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    if (!reportId) return
    let cancelled = false
    fetchReport(reportId)
      .then((loaded) => {
        if (cancelled) return
        setReport(loaded)
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoadError(err instanceof ApiClientError ? err.message : 'Could not load this damage report.')
      })
    return () => { cancelled = true }
  }, [reportId, attempt])

  async function act(run: () => Promise<DamageReport>, done: string) {
    setBusy(true)
    setActionError(null)
    setNotice(null)
    try {
      setReport(await run())
      setNotice(done)
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'That could not be saved. Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  if (loadError) {
    return (
      <section className="page">
        <div role="alert" className="equipment-damage__error">
          <p>The damage report could not be loaded. {loadError}</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      </section>
    )
  }
  if (!report) return <p role="status">Loading damage report…</p>

  const open = report.status === 'OPEN'
  const decide = (event: AffectedEvent, body: DecideOutcomeBody, done: string) =>
    act(() => decideOutcome(report.reportId, event.outcomeId, body), done)

  return (
    <section className="page">
      <PageHeader
        title={`${report.equipmentName}${report.serialNumber ? ` · ${report.serialNumber}` : ''}`}
        description={report.description}
        actions={<Link className="button button--secondary" to="/technical-support/damaged-equipment">Back to reports</Link>}
      />

      <Card className="equipment-damage__summary">
        <EquipmentPhoto name={report.equipmentName} />
        <dl>
          <div><dt>Damaged</dt><dd>{report.serialised ? `Unit ${report.serialNumber}` : `${report.quantity} unit${report.quantity === 1 ? '' : 's'}`}</dd></div>
          <div><dt>From</dt><dd>{formatEventDateTime(report.periodStart)}</dd></div>
          <div><dt>Until</dt><dd>{report.periodEnd ? formatEventDateTime(report.periodEnd) : 'Further notice'}</dd></div>
          <div><dt>Reported</dt><dd>{formatEventDateTime(report.reportedAt)} by {report.reportedByName}</dd></div>
          <div><dt>Status</dt><dd>{open ? 'Open' : 'Closed'}</dd></div>
          {report.markFaulty ? (
            <div><dt>Faulty block</dt><dd>{report.faultyMarked ? 'Unit marked Faulty' : 'Added when the report closes'}</dd></div>
          ) : null}
        </dl>
      </Card>

      {notice ? <Notice>{notice}</Notice> : null}
      {actionError ? <Notice tone="danger">{actionError}</Notice> : null}

      <h2>Affected events</h2>
      {report.affectedEvents.length === 0 ? (
        <p className="field-hint">No event relied on this equipment during the period.</p>
      ) : (
        <ul className="equipment-damage__events" aria-label="Affected events">
          {report.affectedEvents.map((event) => (
            <li key={event.outcomeId}>
              <AffectedEventCard report={report} event={event} open={open} busy={busy} decide={decide} />
            </li>
          ))}
        </ul>
      )}

      {open ? (
        <div className="equipment-damage__close">
          {report.canClose ? null : (
            <p className="field-hint">The report can be closed once every affected event has an outcome
              {report.serialised ? '' : ' and reservations fit the working units'}.</p>
          )}
          <Button disabled={!report.canClose || busy}
            onClick={() => void act(() => closeReport(report.reportId), 'Report closed.')}>
            Close report
          </Button>
        </div>
      ) : null}

    </section>
  )
}

function AffectedEventCard({ report, event, open, busy, decide }: {
  report: DamageReport
  event: AffectedEvent
  open: boolean
  busy: boolean
  decide: (event: AffectedEvent, body: DecideOutcomeBody, done: string) => Promise<void>
}) {
  const undecided = event.outcome === null
  return (
    <Card className="equipment-damage__event">
      <div className="equipment-damage__event-heading">
        <h3>{event.eventName}</h3>
        <span className="chip">{event.active ? outcomeLabel(event.outcome, event.shortfall, event.replacementSerialNumber) : 'No longer active'}</span>
      </div>
      <p className="field-hint">{formatEventTimeRange(event.eventStart, event.eventEnd)}</p>
      <dl>
        <div><dt>Requested</dt><dd>{event.requestedQuantity}</dd></div>
        <div><dt>Reserved</dt><dd>{event.reservedQuantity}</dd></div>
        <div><dt>Affected</dt><dd>{event.affectedQuantity}</dd></div>
        {report.serialised && undecided ? (
          <div><dt>Spares cover it</dt><dd>{event.spareCovers ? 'Yes' : 'No'}</dd></div>
        ) : null}
        {event.reducedBy ? <div><dt>Reduced by</dt><dd>{event.reducedBy}</dd></div> : null}
      </dl>
      {event.resolution ? (
        <p>
          Coordinator: {event.resolution === 'NOTE' ? `resolved with a note: ${event.resolutionNote ?? ''}` : 'requested the shortfall'}
        </p>
      ) : event.additionalRequestId ? (
        <p>Coordinator submitted an additional request ({event.additionalRequestStatus ?? 'processing'}).</p>
      ) : null}
      {!event.active ? (
        <p className="field-hint">This event was cancelled or has finished, so it needs no outcome.</p>
      ) : open && report.serialised && undecided ? (
        <SerialisedActions report={report} event={event} busy={busy} decide={decide} />
      ) : open && !report.serialised ? (
        <ReduceAction event={event} busy={busy} decide={decide} />
      ) : null}
    </Card>
  )
}

function SerialisedActions({ report, event, busy, decide }: {
  report: DamageReport
  event: AffectedEvent
  busy: boolean
  decide: (event: AffectedEvent, body: DecideOutcomeBody, done: string) => Promise<void>
}) {
  const [replacing, setReplacing] = useState(false)
  const [units, setUnits] = useState<EquipmentUnitStatus[] | null>(null)
  const [unitsFailed, setUnitsFailed] = useState(false)
  const [unitsAttempt, setUnitsAttempt] = useState(0)
  const [replacement, setReplacement] = useState('')

  useEffect(() => {
    if (!replacing) return
    let cancelled = false
    fetchUnits(new Date(event.eventStart).toISOString(), new Date(event.eventEnd).toISOString())
      .then((loaded) => {
        if (cancelled) return
        setUnits(loaded.filter((unit) => unit.equipmentId === report.equipmentId
          && unit.serialNumber !== report.serialNumber && unit.status === 'Available'))
        setUnitsFailed(false)
      })
      .catch(() => {
        if (!cancelled) setUnitsFailed(true)
      })
    return () => { cancelled = true }
  }, [replacing, unitsAttempt, event.eventStart, event.eventEnd, report.equipmentId, report.serialNumber])

  const none = { replacementSerialNumber: null, reduceBy: null }
  return (
    <div className="equipment-damage__actions">
      <Button variant="secondary" disabled={busy || !event.spareCovers}
        onClick={() => void decide(event, { decision: 'COVERED', ...none }, `${event.eventName}: covered by spare.`)}>
        Covered by spare
      </Button>
      <Button variant="secondary" disabled={busy} onClick={() => setReplacing(true)}>Replace unit</Button>
      <Button variant="secondary" disabled={busy || event.spareCovers}
        onClick={() => void decide(event, { decision: 'SHORT', ...none }, `${event.eventName}: marked short.`)}>
        Mark short
      </Button>
      {replacing ? (
        <div className="equipment-damage__replace">
          {unitsFailed ? (
            <div role="alert">
              <p>The units could not be loaded.</p>
              <Button variant="secondary" onClick={() => setUnitsAttempt((n) => n + 1)}>Try again</Button>
            </div>
          ) : !units ? (
            <p role="status">Loading units…</p>
          ) : units.length === 0 ? (
            <p>No other unit is available for this event's period. Mark the event short instead.</p>
          ) : (
            <>
              <label className="equipment-damage__field">
                Replacement unit for {event.eventName}
                <select value={replacement} onChange={(e) => setReplacement(e.target.value)}>
                  <option value="">Choose a unit…</option>
                  {units.map((unit) => <option key={unit.serialNumber} value={unit.serialNumber}>{unit.serialNumber}</option>)}
                </select>
              </label>
              <p className="field-hint">The unit must also be free of other reservations for the whole loan window; the server checks this.</p>
              <div className="equipment-damage__actions">
                <Button variant="secondary" disabled={busy} onClick={() => setReplacing(false)}>Cancel</Button>
                <Button disabled={busy || !replacement}
                  onClick={() => void decide(event, { decision: 'REPLACE', replacementSerialNumber: replacement, reduceBy: null },
                    `${event.eventName}: replaced with unit ${replacement}.`)}>
                  Use this unit
                </Button>
              </div>
            </>
          )}
        </div>
      ) : null}
    </div>
  )
}

function ReduceAction({ event, busy, decide }: {
  event: AffectedEvent
  busy: boolean
  decide: (event: AffectedEvent, body: DecideOutcomeBody, done: string) => Promise<void>
}) {
  const [reduceBy, setReduceBy] = useState('0')
  const value = Number(reduceBy)
  const valid = Number.isInteger(value) && value >= 0 && value <= event.reservedQuantity
  return (
    <div className="equipment-damage__actions">
      <label className="equipment-damage__field">
        Units to take off {event.eventName}
        <input type="number" min="0" max={event.reservedQuantity} step="1" value={reduceBy}
          onChange={(e) => setReduceBy(e.target.value)} aria-invalid={!valid} />
      </label>
      {!valid ? <p className="field-error">Enter a whole number from 0 to {event.reservedQuantity}.</p> : null}
      <Button disabled={busy || !valid}
        onClick={() => void decide(event, { decision: 'REDUCE', replacementSerialNumber: null, reduceBy: value },
          `${event.eventName}: reservation updated.`)}>
        Apply
      </Button>
    </div>
  )
}
