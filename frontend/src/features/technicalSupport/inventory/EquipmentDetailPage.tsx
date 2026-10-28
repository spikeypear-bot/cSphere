import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { equipmentMediaFor } from '../../equipment/equipmentCatalogueMedia'
import { formatEventTimeRange } from '../../venueStaff/formatEventDateTime'
import '../damage/equipmentDamage.css'
import { fetchReports } from '../damage/equipmentDamageApi'
import { removeStatusPeriod } from '../status/equipmentStatusApi'
import { DateRangeFields } from '../shared/DateRangeFields'
import { rangeFromParams, rangeToWindow } from '../shared/dateWindow'
import { EquipmentSummaryCard } from '../shared/EquipmentSummaryCard'
import { reportPath, TS } from '../shared/faultyEquipment'
import { describeSpan } from '../shared/format'
import { ResourceView } from '../shared/ResourceView'
import { useResource } from '../shared/useResource'
import { fetchEquipmentDetail, fetchEquipmentReservations } from './inventoryApi'
import '../technicalSupport.css'

/** One kind: its units and their TS03 blocks, its reservations over a
 * window, and its TS09 damage history (D32). */
export function EquipmentDetailPage() {
  const { equipmentId = '' } = useParams<{ equipmentId: string }>()
  const [params, setParams] = useSearchParams()
  const range = rangeFromParams(params)
  const span = rangeToWindow(range)
  const detail = useResource(() => fetchEquipmentDetail(equipmentId), equipmentId)
  const reservations = useResource(
    () => (span ? fetchEquipmentReservations(equipmentId, span.from, span.to) : Promise.resolve([])),
    span ? `${equipmentId}|${span.from}|${span.to}` : 'invalid',
  )
  const reports = useResource(() => fetchReports(equipmentId), equipmentId)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  async function remove(periodId: string, serialNumber: string) {
    setBusy(true)
    setNotice(null)
    setActionError(null)
    try {
      await removeStatusPeriod(periodId)
      setNotice(`${serialNumber} is available again for that period.`)
      detail.retry()
    } catch {
      setActionError('Could not remove that period. Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <ResourceView resource={detail} loadingText="Loading equipment…"
      errorText="This equipment could not be loaded." emptyText="" isEmpty={() => false}>
      {(kind) => (
        <section className="page">
          <PageHeader
            eyebrow="Inventory"
            title={kind.equipmentName}
            actions={(
              <>
                <Link className="button button--secondary" to={`${TS}/inventory?from=${range.from}&to=${range.to}`}>All equipment</Link>
                <Link className="button button--primary" to={`${TS}/faulty/new?equipment=${kind.equipmentId}`}>Report a problem</Link>
              </>
            )}
          />
          <EquipmentSummaryCard name={kind.equipmentName}>
            <dl>
              <div><dt>Category</dt><dd>{equipmentMediaFor(kind.equipmentName)?.category ?? 'Other'}</dd></div>
              <div><dt>Owned</dt><dd>{kind.totalQuantity}</dd></div>
              <div><dt>Tracking</dt><dd>{kind.serialised ? 'By unit' : 'By quantity'}</dd></div>
            </dl>
          </EquipmentSummaryCard>

          {notice ? <Notice>{notice}</Notice> : null}
          {actionError ? <Notice tone="danger">{actionError}</Notice> : null}

          <Card className="tech-card">
            <h2>Units</h2>
            {!kind.serialised ? (
              <p className="field-hint">Units of this kind are not tracked one by one; see its damage history below.</p>
            ) : kind.units.length === 0 ? (
              <p className="field-hint">No units are recorded for this kind.</p>
            ) : (
              <ul className="tech-rows" aria-label="Units">
                {kind.units.map((unit) => (
                  <li key={unit.serialNumber}>
                    <strong>{unit.serialNumber}</strong>
                    <span className={`tech-badge${unit.status === 'Available' ? '' : ' tech-badge--danger'}`}>{unit.status} now</span>
                    <Link to={`${TS}/faulty/new?equipment=${kind.equipmentId}&serial=${encodeURIComponent(unit.serialNumber)}`}>
                      Report a problem with {unit.serialNumber}
                    </Link>
                    {unit.statusPeriods.length === 0 ? (
                      <p className="field-hint">No Faulty or Unavailable periods.</p>
                    ) : unit.statusPeriods.map((period) => (
                      <p key={period.id} className="tech-block">
                        <span><strong>{period.status}</strong>: {describeSpan(period.start, period.end)}</span>
                        <Button type="button" variant="secondary" disabled={busy}
                          onClick={() => void remove(period.id, unit.serialNumber)}>
                          Remove (mark available)
                        </Button>
                      </p>
                    ))}
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card className="tech-card">
            <h2>Reservations</h2>
            <DateRangeFields value={range} onChange={(next) => setParams({ from: next.from, to: next.to }, { replace: true })} />
            {span ? (
              <ResourceView resource={reservations} loadingText="Loading reservations…"
                errorText="The reservations could not be loaded." emptyText="Nothing is reserved in these dates."
                isEmpty={(list) => list.length === 0}>
                {(list) => (
                  <ul className="tech-rows" aria-label="Reservations">
                    {list.map((r) => (
                      <li key={r.logId}>
                        <strong>{r.eventName ?? 'Event'}</strong>
                        <span>{r.quantity} × {r.equipmentName}{r.serialNumber ? ` (unit ${r.serialNumber})` : ''}</span>
                        <p className="field-hint">{formatEventTimeRange(r.loanedFrom, r.loanedUntil)}</p>
                      </li>
                    ))}
                  </ul>
                )}
              </ResourceView>
            ) : null}
          </Card>

          <Card className="tech-card">
            <h2>Damage history</h2>
            <ResourceView resource={reports} loadingText="Loading damage history…"
              errorText="The damage history could not be loaded." emptyText="No damage has been reported for this equipment."
              isEmpty={(list) => list.length === 0}>
              {(list) => (
                <ul className="tech-rows" aria-label="Damage history">
                  {list.map((report) => (
                    <li key={report.reportId}>
                      <Link to={reportPath(report.reportId)}>
                        {report.serialNumber ?? `${report.quantity} unit${report.quantity === 1 ? '' : 's'}`}: {report.description}
                      </Link>
                      <span className="tech-badge">{report.status === 'OPEN' ? 'Open' : 'Closed'}</span>
                      <p className="field-hint">{describeSpan(report.periodStart, report.periodEnd)}</p>
                    </li>
                  ))}
                </ul>
              )}
            </ResourceView>
          </Card>
        </section>
      )}
    </ResourceView>
  )
}
