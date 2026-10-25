import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { PageHeader } from '../../../components/ui/PageHeader'
import { ApiClientError } from '../../../lib/apiClient'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { EquipmentPhoto } from './EquipmentPhoto'
import { fetchReports } from './equipmentDamageApi'
import type { DamageReport } from './equipmentDamage.types'
import './equipmentDamage.css'

/** TS09: every damage report, newest first (the server's order). */
export function DamageReportsPage() {
  const [reports, setReports] = useState<DamageReport[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    fetchReports()
      .then((loaded) => {
        if (cancelled) return
        setReports(loaded)
        setError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiClientError ? err.message : 'Could not load damage reports.')
      })
    return () => { cancelled = true }
  }, [attempt])

  return (
    <section className="page">
      <PageHeader
        title="Damaged equipment"
        description="Report damaged equipment and decide what happens to each event that relied on it."
        actions={
          <Link className="button button--primary" to="/technical-support/damaged-equipment/new">
            Report damaged equipment
          </Link>
        }
      />
      {error ? (
        <div role="alert" className="equipment-damage__error">
          <p>The damage reports could not be loaded. {error}</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      ) : !reports ? (
        <p role="status">Loading damage reports…</p>
      ) : reports.length === 0 ? (
        <p className="field-hint">No damaged equipment has been reported.</p>
      ) : (
        <>
          <ul className="equipment-damage__list" aria-label="Damage reports">
            {reports.map((report) => {
              const waiting = report.affectedEvents.filter((e) => e.active && e.outcome === null).length
              return (
                <li key={report.reportId}>
                  <Card className="equipment-damage__report-card">
                    <EquipmentPhoto name={report.equipmentName} />
                    <div>
                      <h2>
                        <Link to={`/technical-support/damaged-equipment/${report.reportId}`}>
                          {report.equipmentName}{report.serialNumber ? ` · ${report.serialNumber}` : ` · ${report.quantity} damaged`}
                        </Link>
                      </h2>
                      <p>{report.description}</p>
                      <p className="field-hint">
                        Reported {formatEventDateTime(report.reportedAt)} by {report.reportedByName}
                        {' · '}{report.affectedEvents.length} affected event{report.affectedEvents.length === 1 ? '' : 's'}
                        {waiting ? ` · ${waiting} awaiting a decision` : ''}
                      </p>
                    </div>
                    <span className={`chip equipment-damage__status equipment-damage__status--${report.status.toLowerCase()}`}>
                      {report.status === 'OPEN' ? 'Open' : 'Closed'}
                    </span>
                  </Card>
                </li>
              )
            })}
          </ul>
        </>
      )}
    </section>
  )
}
