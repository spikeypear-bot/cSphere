import { Link, useLocation } from 'react-router-dom'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { EquipmentPhoto } from '../damage/EquipmentPhoto'
import { fetchReports } from '../damage/equipmentDamageApi'
import { fetchCurrentStatusPeriods } from '../status/equipmentStatusApi'
import { equipmentPath, reportPath, TS } from '../shared/faultyEquipment'
import { describeSpan } from '../shared/format'
import { ResourceView } from '../../../components/ui/ResourceView'
import { RowLink } from '../../../components/ui/RowLink'
import { useResource } from '../../../lib/useResource'
import '../damage/equipmentDamage.css'
import '../technicalSupport.css'

/** Equipment out of action: TS03 blocks on units that are running or yet to
 * start, and every TS09 damage report (newest first, the server's order). */
export function FaultyEquipmentPage() {
  const location = useLocation()
  const notice = (location.state as { notice?: string } | null)?.notice ?? null
  const blocks = useResource(fetchCurrentStatusPeriods, 'blocks')
  const reports = useResource(() => fetchReports(), 'reports')

  return (
    <section className="page">
      <PageHeader
        title="Faulty equipment"
        description="Units marked Faulty or Unavailable, and damage reports with what was decided for each affected event."
        actions={<Link className="button button--primary" to={`${TS}/faulty/new`}>Report a problem</Link>}
      />
      {notice ? <Notice>{notice}</Notice> : null}

      <h2 className="section-title">Blocked units</h2>
      <ResourceView resource={blocks} loadingText="Loading blocked units…"
        errorText="The blocked units could not be loaded." emptyText="No unit is marked Faulty or Unavailable."
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="equipment-damage__list" aria-label="Blocked units">
            {list.map((block) => (
              <li key={block.id}>
                <RowLink to={equipmentPath(block.equipmentId)} className="card tech-row-card" titleAs="h3"
                  title={`${block.equipmentName} · ${block.serialNumber}`}
                  media={<EquipmentPhoto name={block.equipmentName} />}
                  badges={<span className="dash-badge dash-badge--danger">{block.status}</span>}>
                  <p className="tech-row-card__hint">{describeSpan(block.start, block.end)}</p>
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>

      <h2 className="section-title">Damage reports</h2>
      <ResourceView resource={reports} loadingText="Loading damage reports…"
        errorText="The damage reports could not be loaded." emptyText="No damaged equipment has been reported."
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="equipment-damage__list" aria-label="Damage reports">
            {list.map((report) => {
              const waiting = report.affectedEvents.filter((e) => e.active && e.outcome === null).length
              return (
                <li key={report.reportId}>
                  <RowLink to={reportPath(report.reportId)} className="card tech-row-card" titleAs="h3"
                    title={`${report.equipmentName}${report.serialNumber ? ` · ${report.serialNumber}` : ` · ${report.quantity} damaged`}`}
                    media={<EquipmentPhoto name={report.equipmentName} />}
                    badges={(
                      <span className={report.status === 'OPEN' ? 'dash-badge dash-badge--danger' : 'dash-badge dash-badge--neutral'}>
                        {report.status === 'OPEN' ? 'Open' : 'Closed'}
                      </span>
                    )}>
                    <p>{report.description}</p>
                    <p className="tech-row-card__hint">{describeSpan(report.periodStart, report.periodEnd)}</p>
                    <p className="tech-row-card__hint">
                      Reported {formatEventDateTime(report.reportedAt)} by {report.reportedByName}
                      {' · '}{report.affectedEvents.length} affected event{report.affectedEvents.length === 1 ? '' : 's'}
                      {waiting ? ` · ${waiting} awaiting a decision` : ''}
                    </p>
                  </RowLink>
                </li>
              )
            })}
          </ul>
        )}
      </ResourceView>
    </section>
  )
}
