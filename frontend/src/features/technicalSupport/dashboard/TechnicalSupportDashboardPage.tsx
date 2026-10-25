import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Card } from '../../../components/ui/Card'
import { PageHeader } from '../../../components/ui/PageHeader'
import { formatEventTimeRange } from '../../venueStaff/formatEventDateTime'
import { fetchReports } from '../damage/equipmentDamageApi'
import { fetchCatalogueForWindow } from '../inventory/inventoryApi'
import { fetchEquipmentRequests } from '../reservations/reservationApi'
import { summariseItems } from '../reservations/reservationUtils'
import { fetchCurrentStatusPeriods } from '../status/equipmentStatusApi'
import { rangeFromToday, rangeToWindow } from '../shared/dateWindow'
import { mergeFaulty, TS } from '../shared/faultyEquipment'
import { describeSpan, requesterName } from '../shared/format'
import { ResourceView } from '../shared/ResourceView'
import { useResource } from '../shared/useResource'
import '../technicalSupport.css'

const TOP = 5

/** The console's home: what needs attention now, each card linking on. */
export function TechnicalSupportDashboardPage() {
  return (
    <section className="page">
      <PageHeader
        title="Technical Support Staff console"
        description="Incoming equipment requests, equipment out of action, and what is in stock today."
      />
      <div className="tech-dashboard">
        <IncomingRequestsCard />
        <FaultyEquipmentCard />
        <InventoryCard />
      </div>
    </section>
  )
}

function DashboardCard({ title, to, children }: { title: string; to: string; children: ReactNode }) {
  return (
    <Card className="tech-card tech-dashboard__card">
      <h2><Link to={to}>{title}</Link></h2>
      {children}
    </Card>
  )
}

/** Awaiting review, most urgent (soonest event) first: the server's order. */
function IncomingRequestsCard() {
  const requests = useResource(() => fetchEquipmentRequests('processing'), 'processing')
  return (
    <DashboardCard title="Incoming requests" to={`${TS}/requests`}>
      <ResourceView resource={requests} loadingText="Loading requests…"
        errorText="The requests could not be loaded." emptyText="No equipment requests are awaiting review."
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="tech-rows" aria-label="Incoming requests">
            {list.slice(0, TOP).map((request) => (
              <li key={request.requestId}>
                <Link to={`${TS}/requests/${request.requestId}`}>{request.eventName}</Link>
                {request.isShortfallRequest ? <span className="tech-badge">Shortfall</span> : null}
                <p className="field-hint">{formatEventTimeRange(request.eventStart, request.eventEnd)}</p>
                <p className="field-hint">
                  {requesterName(request.requestedBy)} · {summariseItems(request.items)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </DashboardCard>
  )
}

/** Current blocks and open damage reports, newest first. */
function FaultyEquipmentCard() {
  const faulty = useResource(
    () => Promise.all([fetchCurrentStatusPeriods(), fetchReports()]).then(([periods, reports]) => mergeFaulty(periods, reports)),
    'faulty',
  )
  return (
    <DashboardCard title="Faulty equipment" to={`${TS}/faulty`}>
      <ResourceView resource={faulty} loadingText="Loading faulty equipment…"
        errorText="Faulty equipment could not be loaded." emptyText="No equipment is out of action."
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="tech-rows" aria-label="Faulty equipment">
            {list.slice(0, TOP).map((entry) => (
              <li key={entry.key}>
                <Link to={entry.linkTo}>{entry.equipmentName} · {entry.what}</Link>
                <span className="tech-badge">{entry.label}</span>
                <p className="field-hint">{describeSpan(entry.start, entry.end)}</p>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </DashboardCard>
  )
}

/** Today's stock: today → tomorrow. */
function InventoryCard() {
  const today = rangeToWindow(rangeFromToday(0))!
  const catalogue = useResource(() => fetchCatalogueForWindow(today.from, today.to), today.from)
  return (
    <DashboardCard title="Inventory" to={`${TS}/inventory`}>
      <ResourceView resource={catalogue} loadingText="Loading inventory…"
        errorText="The inventory could not be loaded." emptyText="The equipment catalogue is empty."
        isEmpty={(items) => items.length === 0}>
        {(items) => {
          const sum = (pick: (item: typeof items[number]) => number) => items.reduce((total, item) => total + pick(item), 0)
          const none = items.filter((item) => item.availableQuantity === 0)
          return (
            <>
              <dl className="tech-figures" aria-label="Inventory today">
                <div><dt>Kinds</dt><dd>{items.length}</dd></div>
                <div><dt>Units available today</dt><dd>{sum((item) => item.availableQuantity)}</dd></div>
                <div><dt>Reserved</dt><dd>{sum((item) => item.reservedQuantity)}</dd></div>
                <div><dt>Out of action</dt><dd>{sum((item) => item.outOfActionQuantity)}</dd></div>
              </dl>
              {none.length > 0 ? (
                <p className="field-hint">None left today: {none.map((item) => item.equipmentName).join(', ')}</p>
              ) : null}
            </>
          )
        }}
      </ResourceView>
    </DashboardCard>
  )
}
