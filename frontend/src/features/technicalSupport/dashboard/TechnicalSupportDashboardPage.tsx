import { useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../../../components/ui/PageHeader'
import { useSession } from '../../../lib/sessionContext'
import { EquipmentPhoto } from '../damage/EquipmentPhoto'
import { fetchReports } from '../damage/equipmentDamageApi'
import { fetchCatalogueForWindow } from '../inventory/inventoryApi'
import type { CatalogueWindowItem } from '../inventory/inventory.types'
import type { EquipmentRequestSummary } from '../reservations/reservation.types'
import { fetchEquipmentRequests } from '../reservations/reservationApi'
import { fetchCurrentStatusPeriods } from '../status/equipmentStatusApi'
import { rangeFromToday, rangeToWindow } from '../shared/dateWindow'
import { equipmentPath, mergeFaulty, TS, type FaultyEntry } from '../shared/faultyEquipment'
import { countOf, describeUntil, describeWhen, greetingFor, previewItems, requesterName } from '../shared/format'
import { ResourceView } from '../shared/ResourceView'
import { RowLink } from '../shared/RowLink'
import { useResource, type Resource } from '../shared/useResource'
import '../damage/equipmentDamage.css'
import '../technicalSupport.css'

/** How many rows each block shows before "View all". */
const TOP = 5

/** The console's front page: a work queue. Requests awaiting review first,
 * then what is running low today and what is out of action. */
export function TechnicalSupportDashboardPage() {
  const { username } = useSession()
  const requests = useResource(() => fetchEquipmentRequests('processing'), 'processing')
  const faulty = useResource(
    () => Promise.all([fetchCurrentStatusPeriods(), fetchReports()]).then(([periods, reports]) => mergeFaulty(periods, reports)),
    'faulty',
  )
  const greeting = greetingFor()

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(requests, faulty)}</p>}
      />
      <div className="tech-home">
        <RequestsPanel requests={requests} />
        <TodayPanel />
        <FaultyPanel faulty={faulty} />
      </div>
    </section>
  )
}

/** "3 requests to handle · 2 items out of action", from whichever has loaded. */
function summarise(requests: Resource<EquipmentRequestSummary[]>, faulty: Resource<FaultyEntry[]>): string {
  if (requests.loading && faulty.loading) return 'Checking what needs your attention…'
  const parts = [
    requests.data ? `${countOf(requests.data.length, 'request')} to handle` : null,
    faulty.data ? `${countOf(faulty.data.length, 'item')} out of action` : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : 'Some of what needs your attention could not be loaded.'
}

/** A titled block of the front page with a quiet "View all" link. */
function Panel({ title, action, actionName, to, className, children }: {
  title: string
  /** The visible action text, e.g. "View all". */
  action: string
  /** The action's full name for assistive technology. */
  actionName: string
  to: string
  className?: string
  children: ReactNode
}) {
  const id = useId()
  return (
    <section className={className ? `card tech-panel ${className}` : 'card tech-panel'} aria-labelledby={id}>
      <div className="tech-panel__head">
        <h2 id={id}>{title}</h2>
        <Link className="button button--ghost tech-panel__action" to={to} aria-label={actionName}>
          {action} <span aria-hidden="true">→</span>
        </Link>
      </div>
      <div className="tech-panel__body">{children}</div>
    </section>
  )
}

/** Awaiting review, most urgent (soonest event) first: the server's order. */
function RequestsPanel({ requests }: { requests: Resource<EquipmentRequestSummary[]> }) {
  return (
    <Panel title="Requests to handle" action="View all" actionName="View all equipment requests"
      to={`${TS}/requests`} className="tech-home__wide">
      <ResourceView resource={requests} loadingText="Loading requests…"
        errorText="The requests could not be loaded." emptyText="Nothing to handle right now."
        emptyClassName="tech-empty" isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="tech-queue" aria-label="Requests to handle">
            {list.slice(0, TOP).map((request) => (
              <li key={request.requestId}>
                <RowLink to={`${TS}/requests/${request.requestId}`} className="tech-queue__row" title={request.eventName}
                  badges={request.isShortfallRequest ? <span className="tech-badge tech-badge--danger">Shortfall</span> : null}>
                  <p className="tech-queue__meta">
                    <span className="tech-queue__when">{describeWhen(request.eventStart, request.eventEnd)}</span>
                    <span>{requesterName(request.requestedBy)} · {previewItems(request.items)}</span>
                  </p>
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </Panel>
  )
}

/** Current blocks and open damage reports, newest first. */
function FaultyPanel({ faulty }: { faulty: Resource<FaultyEntry[]> }) {
  return (
    <Panel title="Faulty equipment" action="View all" actionName="View all faulty equipment" to={`${TS}/faulty`}>
      <ResourceView resource={faulty} loadingText="Loading faulty equipment…"
        errorText="Faulty equipment could not be loaded." emptyText="No equipment out of action."
        emptyClassName="tech-empty" isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="tech-queue" aria-label="Faulty equipment">
            {list.slice(0, TOP).map((entry) => (
              <li key={entry.key}>
                <RowLink to={entry.linkTo} className="tech-queue__row" title={entry.equipmentName}
                  media={<EquipmentPhoto name={entry.equipmentName} />}
                  badges={<span className={entry.kind === 'block' ? 'tech-badge tech-badge--danger' : 'tech-badge'}>{entry.label}</span>}>
                  <p>{entry.what} · <span className="tech-nowrap">{describeUntil(entry.end)}</span></p>
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </Panel>
  )
}

/** Kinds with the fewest units left today, fewest first, then by name. */
function lowest(items: CatalogueWindowItem[]): CatalogueWindowItem[] {
  return [...items]
    .sort((a, b) => a.availableQuantity - b.availableQuantity || a.equipmentName.localeCompare(b.equipmentName))
    .slice(0, TOP)
}

/** Today's stock (today → tomorrow): units free now and what is running low. */
function TodayPanel() {
  const today = rangeFromToday(0)
  const span = rangeToWindow(today)!
  const catalogue = useResource(() => fetchCatalogueForWindow(span.from, span.to), span.from)
  const query = `from=${today.from}&to=${today.to}`
  const lowestId = useId()
  return (
    <Panel title="Equipment today" action="View" actionName="View inventory" to={`${TS}/inventory`}>
      <ResourceView resource={catalogue} loadingText="Loading today's equipment…"
        errorText="The inventory could not be loaded." emptyText="The equipment catalogue is empty."
        emptyClassName="tech-empty" isEmpty={(items) => items.length === 0}>
        {(items) => {
          const available = items.reduce((total, item) => total + item.availableQuantity, 0)
          const total = items.reduce((total, item) => total + item.totalQuantity, 0)
          return (
            <>
              <p className="tech-today__total">
                <strong>{available}</strong> of {total} units available
              </p>
              <h3 className="tech-today__heading" id={lowestId}>Lowest today</h3>
              <ul className="tech-queue" aria-labelledby={lowestId}>
                {lowest(items).map((item) => (
                  <li key={item.equipmentId}>
                    <RowLink to={`${equipmentPath(item.equipmentId)}?${query}`} className="tech-queue__row tech-queue__row--compact"
                      title={item.equipmentName} media={<EquipmentPhoto name={item.equipmentName} />}
                      aside={(
                        <span className={item.availableQuantity === 0 ? 'tech-left tech-left--none' : 'tech-left'}>
                          {item.availableQuantity} left
                        </span>
                      )} />
                  </li>
                ))}
              </ul>
            </>
          )
        }}
      </ResourceView>
    </Panel>
  )
}
