import { Fragment, useEffect, useRef, useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { EquipmentPhoto } from '../damage/EquipmentPhoto'
import { countOf } from '../../../lib/greeting'
import { describeWhen } from '../shared/format'
import {
  describeReserved,
  groupReservations,
  maxNow,
  NO_CHOICE,
  nowOf,
  planBatch,
  rowProblem,
  unitsFor,
  type FulfilmentRow,
  type RowChoice,
  type UnitView,
} from './fulfilment'
import type { EquipmentReservation, EquipmentRequestSummary, RequestLine, TimePeriod } from './reservation.types'
import { ApiError, reserveBatch, toIso } from './reservationApi'
import { describeReserveError, toLocalInputValue } from './reservationUtils'
import '../damage/equipmentDamage.css'
import '../technicalSupport.css'
import './fulfilment.css'

type Result = { tone: 'success' | 'danger'; text: string; equipmentId: string | null }

/**
 * TS02 (D33): one table with a row per requested item. Each row shows what
 * was requested, what is reserved (spares apart) and what is available for
 * the loan window, and how many to reserve now; one button reserves every
 * row in a single all-or-nothing batch. Serialised rows show the units that
 * will be reserved and can choose others; any row can add spares. A request
 * that is no longer awaiting review shows the same figures, read-only.
 */
export function ReserveCard({ request, rows, lines, reservations, loaded, loadFailed, onRetry, period, onPeriodChange, periodValid, onRefresh }: {
  request: EquipmentRequestSummary
  /** Null until the first load. */
  rows: FulfilmentRow[] | null
  lines: RequestLine[]
  reservations: EquipmentReservation[]
  /** False while figures for the current window are (re)loading. */
  loaded: boolean
  loadFailed: boolean
  onRetry: () => void
  period: TimePeriod
  onPeriodChange: (period: TimePeriod) => void
  periodValid: boolean
  onRefresh: () => void
}) {
  const editable = request.status === 'processing'
  const [choices, setChoices] = useState<Record<string, RowChoice>>({})
  const [panel, setPanel] = useState<Record<string, 'units' | 'choose' | undefined>>({})
  const [windowOpen, setWindowOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const messageRef = useRef<HTMLParagraphElement>(null)

  useEffect(() => {
    if (result) messageRef.current?.focus()
  }, [result])

  const choiceOf = (id: string) => choices[id] ?? NO_CHOICE
  const setChoice = (id: string, change: Partial<RowChoice>) => {
    setChoices((current) => ({ ...current, [id]: { ...(current[id] ?? NO_CHOICE), ...change } }))
    setResult((current) => (current?.tone === 'danger' ? null : current))
  }
  const togglePanel = (id: string, which: 'units' | 'choose') =>
    setPanel((current) => ({ ...current, [id]: current[id] === which ? undefined : which }))

  const list = rows ?? []
  const problems = Object.fromEntries(list.map((row) => [row.line.equipmentId, rowProblem(row, choiceOf(row.line.equipmentId))]))
  const plan = planBatch(list, choices)
  const units = plan.reduce((total, line) => total + line.quantity + line.spare, 0)
  const blocked = Object.values(problems).some((problem) => problem !== null)
  const toReserve = list.filter((row) => row.remaining > 0).length

  async function reserveAll() {
    setSaving(true)
    setResult(null)
    try {
      const saved = await reserveBatch(request.eventId, period, plan)
      const count = saved.reduce((total, r) => total + r.quantity, 0)
      setChoices({})
      setPanel({})
      setResult({ tone: 'success', text: `Reserved ${countOf(count, 'unit')} for this event.`, equipmentId: null })
      onRefresh()
    } catch (error) {
      const conflict = error instanceof ApiError && error.status === 409
      setResult({
        tone: 'danger',
        text: describeReserveError(error) + (conflict ? '. Nothing was reserved; the figures below are refreshed.' : ''),
        equipmentId: error instanceof ApiError ? error.equipmentId : null,
      })
      // Someone else may have taken stock: show what is free now.
      if (conflict) onRefresh()
    } finally {
      setSaving(false)
    }
  }

  const eventWindow = { start: toLocalInputValue(request.eventStart), end: toLocalInputValue(request.eventEnd) }
  const isEventWindow = period.start === eventWindow.start && period.end === eventWindow.end
  const failedRow = result?.tone === 'danger' ? result.equipmentId : null
  const failedName = failedRow ? list.find((row) => row.line.equipmentId === failedRow)?.line.equipmentName : undefined

  return (
    <Card className="tech-card fulfil">
      <div className="tech-heading-row">
        <h2 id="reserve-heading">{editable ? 'Reserve equipment' : 'Equipment'}</h2>
        {rows && lines.length > 0 ? (
          <span className={`dash-badge${toReserve === 0 ? ' dash-badge--success' : ' dash-badge--neutral'}`}>
            {toReserve === 0 ? 'All reserved' : `${countOf(toReserve, 'item')} to reserve`}
          </span>
        ) : null}
      </div>

      {editable ? (
        <div className="fulfil-window">
          <p>
            <span className="fulfil-window__label">Loan window</span>{' '}
            {periodValid ? describeWhen(toIso(period.start), toIso(period.end)) : 'Not set'}
            {isEventWindow ? <span className="field-hint"> · the event&rsquo;s times</span> : null}
          </p>
          <Button variant="ghost" type="button" aria-expanded={windowOpen} aria-controls="loan-window"
            onClick={() => setWindowOpen((open) => !open)}>
            {windowOpen ? 'Done' : 'Change'}
          </Button>
          {windowOpen ? (
            <div id="loan-window" className="fulfil-window__fields">
              <div className="field">
                <label htmlFor="period-start">Start</label>
                <input id="period-start" type="datetime-local" value={period.start} aria-invalid={!periodValid}
                  onChange={(e) => onPeriodChange({ ...period, start: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="period-end">End</label>
                <input id="period-end" type="datetime-local" value={period.end} aria-invalid={!periodValid}
                  aria-describedby={periodValid ? undefined : 'period-error'}
                  onChange={(e) => onPeriodChange({ ...period, end: e.target.value })} />
              </div>
              {!isEventWindow ? (
                <Button variant="secondary" type="button" onClick={() => onPeriodChange(eventWindow)}>Use the event&rsquo;s times</Button>
              ) : null}
            </div>
          ) : null}
          {!periodValid ? <p id="period-error" role="alert" className="error-text">Choose an end time after the start time.</p> : null}
        </div>
      ) : null}

      {loadFailed ? (
        <div role="alert" className="dash-load-error">
          <p>The equipment figures could not be loaded.</p>
          <Button variant="secondary" type="button" onClick={onRetry}>Try again</Button>
        </div>
      ) : null}
      {!rows && !loadFailed && periodValid ? <p role="status">Loading equipment…</p> : null}

      {rows && lines.length === 0 ? (
        <p className="field-hint">This request lists no itemised equipment. Review its technical note instead.</p>
      ) : null}

      {rows && lines.length > 0 ? (
        <table className="fulfil-table" aria-labelledby="reserve-heading" aria-busy={!loaded}>
          <thead>
            <tr>
              <th scope="col">Item</th>
              <th scope="col" className="fulfil-num">Requested</th>
              <th scope="col" className="fulfil-num">Reserved</th>
              <th scope="col" className="fulfil-num">Available</th>
              {editable ? <th scope="col">Reserve now</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const id = row.line.equipmentId
              const choice = choiceOf(id)
              const problem = problems[id]
              const serverError = failedRow === id ? result!.text : null
              const message = problem ?? serverError
              const open = panel[id]
              const columns = editable ? 5 : 4
              return (
                <Fragment key={id}>
                  <tr className={`fulfil-row${message ? ' fulfil-row--error' : ''}${open ? ' fulfil-row--open' : ''}`}>
                    <th scope="row" className="fulfil-item">
                      <div className="fulfil-item__wrap">
                      <EquipmentPhoto name={row.line.equipmentName} />
                      <span className="fulfil-item__text">
                        <span className="fulfil-item__name">{row.line.equipmentName}</span>
                        <span className="field-hint">{row.serialised ? 'By serial number' : 'By quantity'}</span>
                        {row.serialised ? (
                          <button type="button" className="fulfil-link" aria-expanded={open === 'units'}
                            aria-controls={`units-${id}`} onClick={() => togglePanel(id, 'units')}>
                            Units <span aria-hidden="true">{open === 'units' ? '▴' : '▾'}</span>
                          </button>
                        ) : null}
                      </span>
                      </div>
                    </th>
                    <td className="fulfil-num" data-label="Requested">{row.line.quantity}</td>
                    <td className="fulfil-num" data-label="Reserved">
                      {row.spareReserved > 0 ? (
                        <>{row.reserved} <span className="fulfil-spare">+ {row.spareReserved} spare</span></>
                      ) : describeReserved(row)}
                    </td>
                    <td className="fulfil-num" data-label="Available">
                      {row.available === null ? '—' : row.available}
                    </td>
                    {editable ? (
                      <td className="fulfil-now" data-label="Reserve now">
                        <ReserveNow row={row} choice={choice} message={message}
                          onNow={(now) => setChoice(id, { now })}
                          onSpare={(spare) => setChoice(id, { spare })}
                          onChoose={() => {
                            if (open !== 'choose' && choice.serials === null) setChoice(id, { serials: unitsFor(row, choice) })
                            togglePanel(id, 'choose')
                          }}
                          choosing={open === 'choose'} />
                      </td>
                    ) : null}
                  </tr>
                  {open === 'units' ? (
                    <tr className="fulfil-detail">
                      <td colSpan={columns} id={`units-${id}`}>
                        <UnitList units={row.units} />
                      </td>
                    </tr>
                  ) : null}
                  {open === 'choose' && editable ? (
                    <tr className="fulfil-detail">
                      <td colSpan={columns} id={`choose-${id}`}>
                        <UnitChooser row={row} choice={choice}
                          onChange={(serials) => setChoice(id, { serials })}
                          onAuto={() => setChoice(id, { serials: null })}
                          onDone={() => togglePanel(id, 'choose')} />
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      ) : null}

      {editable && rows && lines.length > 0 ? (
        <div className="fulfil-actions">
          <Button type="button" onClick={reserveAll}
            disabled={units === 0 || blocked || saving || !loaded || !periodValid}>
            {saving ? 'Reserving…' : units === 0 ? 'Nothing to reserve' : `Reserve all remaining (${countOf(units, 'unit')})`}
          </Button>
          {!loaded && rows ? <span className="field-hint" role="status">Updating figures…</span> : null}
          {units === 0 && toReserve === 0 && loaded ? <span className="field-hint">Nothing left to reserve. Add spares with “+ spare”.</span> : null}
        </div>
      ) : null}

      {result ? (
        <p ref={messageRef} tabIndex={-1} className={`notice notice--${result.tone}`}
          role={result.tone === 'danger' ? 'alert' : 'status'}>
          {failedName ? `${failedName}: ` : ''}{result.text}
        </p>
      ) : null}

      <ReservedList reservations={reservations} lines={lines} request={request} />
    </Card>
  )
}

function ReserveNow({ row, choice, message, onNow, onSpare, onChoose, choosing }: {
  row: FulfilmentRow
  choice: RowChoice
  message: string | null
  onNow: (now: number) => void
  onSpare: (spare: number) => void
  onChoose: () => void
  choosing: boolean
}) {
  const id = row.line.equipmentId
  const now = nowOf(row, choice)
  const picked = unitsFor(row, choice)
  const roomForSpare = now + choice.spare < row.capacity
  const errorId = `reserve-error-${id}`
  return (
    <div className="fulfil-now__body">
      {row.remaining === 0 ? (
        <span className="fulfil-done">✓ Reserved</span>
      ) : (
        <span className="fulfil-now__input">
          <label className="visually-hidden" htmlFor={`now-${id}`}>Reserve now: {row.line.equipmentName}</label>
          <input id={`now-${id}`} type="number" inputMode="numeric" min={0} max={maxNow(row)} step={1}
            value={Number.isNaN(now) ? '' : now}
            aria-invalid={message !== null}
            aria-describedby={message ? errorId : `remaining-${id}`}
            onChange={(e) => onNow(e.target.value === '' ? 0 : Number(e.target.value))} />
          <span id={`remaining-${id}`} className="field-hint">of {row.remaining} left</span>
        </span>
      )}

      {row.serialised && picked.length > 0 ? (
        <ul className="fulfil-chips" aria-label={`Units to reserve: ${row.line.equipmentName}`}>
          {picked.map((serial, index) => (
            <li key={serial} className={`fulfil-chip${index >= now ? ' fulfil-chip--spare' : ''}`}>
              {serial}
              {index >= now ? <span className="fulfil-chip__tag">spare</span>
                : choice.serials === null ? <span className="fulfil-chip__tag">auto</span> : null}
            </li>
          ))}
        </ul>
      ) : null}

      <span className="fulfil-now__tools">
        {row.serialised && row.freeSerials.length > 0 && now + choice.spare > 0 ? (
          <button type="button" className="fulfil-link" aria-expanded={choosing} aria-controls={`choose-${id}`}
            onClick={onChoose}>
            <span aria-hidden="true">✎ </span>Choose units
          </button>
        ) : null}
        {choice.spare === 0 ? (
          <button type="button" className="fulfil-link" disabled={!roomForSpare}
            aria-label={`Add a spare ${row.line.equipmentName}`} onClick={() => onSpare(1)}>
            + spare
          </button>
        ) : (
          <span className="fulfil-stepper" role="group" aria-label={`Spares: ${row.line.equipmentName}`}>
            <button type="button" aria-label="One fewer spare" onClick={() => onSpare(choice.spare - 1)}>−</button>
            <span className="fulfil-spare">{choice.spare} spare</span>
            <button type="button" aria-label="One more spare" disabled={!roomForSpare} onClick={() => onSpare(choice.spare + 1)}>+</button>
          </span>
        )}
      </span>
      {message ? <p id={errorId} className="field-error">{message}</p> : null}
      {!message && row.capacity < row.remaining ? (
        <p className="fulfil-short">
          {row.capacity === 0
            ? `None free for this window: all ${row.remaining} still needed are short.`
            : `Short: only ${row.capacity} of the ${row.remaining} still needed are free for this window.`}
        </p>
      ) : null}
    </div>
  )
}

const UNIT_LABEL: Record<UnitView['state']['kind'], string> = {
  free: 'Free',
  here: 'Reserved for this event',
  elsewhere: 'Reserved',
  blocked: '',
}

function unitLabel(unit: UnitView): string {
  switch (unit.state.kind) {
    case 'here': return unit.state.spare ? 'Spare for this event' : UNIT_LABEL.here
    case 'elsewhere': return unit.state.eventName ? `Reserved: ${unit.state.eventName}` : UNIT_LABEL.elsewhere
    case 'blocked': return unit.state.status
    default: return UNIT_LABEL.free
  }
}

/** Every unit of the item and what it is doing during the loan window. */
function UnitList({ units }: { units: UnitView[] }) {
  if (units.length === 0) return <p className="field-hint">No units of this item are recorded.</p>
  return (
    <ul className="fulfil-units">
      {units.map((unit) => (
        <li key={unit.serialNumber} className={`fulfil-unit fulfil-unit--${unit.state.kind}`}>
          <span className="fulfil-unit__serial">{unit.serialNumber}</span>
          <span>{unitLabel(unit)}</span>
        </li>
      ))}
    </ul>
  )
}

/** Pick exactly which free, working units to reserve. */
function UnitChooser({ row, choice, onChange, onAuto, onDone }: {
  row: FulfilmentRow
  choice: RowChoice
  onChange: (serials: string[]) => void
  onAuto: () => void
  onDone: () => void
}) {
  const needed = nowOf(row, choice) + choice.spare
  const chosen = choice.serials ?? []
  return (
    <fieldset className="fulfil-chooser">
      <legend>Choose {countOf(needed, 'unit')} of {row.line.equipmentName} ({chosen.length} chosen)</legend>
      <div className="fulfil-chooser__options">
        {row.freeSerials.map((serial) => {
          const checked = chosen.includes(serial)
          return (
            <label key={serial} className={`fulfil-option${checked ? ' fulfil-option--checked' : ''}`}>
              <input type="checkbox" checked={checked} disabled={!checked && chosen.length >= needed}
                onChange={() => onChange(checked ? chosen.filter((s) => s !== serial) : [...chosen, serial])} />
              {serial}
            </label>
          )
        })}
      </div>
      <p className="field-hint">Only free, working units are listed. The first in serial order are for the request; any beyond it are spares.</p>
      <div className="tech-actions">
        <Button variant="secondary" type="button" onClick={onDone}>Done</Button>
        <Button variant="ghost" type="button" onClick={() => { onAuto(); onDone() }}>Pick automatically</Button>
      </div>
    </fieldset>
  )
}

/** TS02 AC11: what is already reserved for the event, grouped by item. */
function ReservedList({ reservations, lines, request }: {
  reservations: EquipmentReservation[]
  lines: RequestLine[]
  request: EquipmentRequestSummary
}) {
  const groups = groupReservations(reservations, lines)
  const eventFrom = new Date(request.eventStart).getTime()
  const eventTo = new Date(request.eventEnd).getTime()
  return (
    <section className="fulfil-reserved" aria-labelledby="reserved-heading">
      <h3 id="reserved-heading">Reserved for this event</h3>
      {groups.length === 0 ? <p className="field-hint">Nothing yet.</p> : (
        <ul className="fulfil-reserved__list">
          {groups.map((group) => (
            <li key={group.equipmentId}>
              <span className="fulfil-reserved__name">{group.equipmentName}</span>
              <span className="fulfil-chips">
                {group.reservations.map((r) => (
                  <span key={r.logId} className={`fulfil-chip${r.spare ? ' fulfil-chip--spare' : ''}`}>
                    {r.serialNumber ?? countOf(r.quantity, 'unit')}
                    {r.spare ? <span className="fulfil-chip__tag">spare</span> : null}
                  </span>
                ))}
              </span>
              {group.reservations.some((r) => new Date(r.loanedFrom).getTime() !== eventFrom
                || new Date(r.loanedUntil).getTime() !== eventTo) ? (
                <span className="field-hint">
                  {[...new Set(group.reservations.map((r) => describeWhen(r.loanedFrom, r.loanedUntil)))].join('; ')}
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
