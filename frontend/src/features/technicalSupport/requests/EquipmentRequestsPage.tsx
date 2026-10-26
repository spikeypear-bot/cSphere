import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../../components/ui/PageHeader'
import {
  EQUIPMENT_REQUEST_FILTER_LABELS,
  EQUIPMENT_REQUEST_FILTERS,
  type EquipmentRequestFilter,
} from '../reservations/reservation.types'
import { fetchEquipmentRequests } from '../reservations/reservationApi'
import { TS } from '../shared/faultyEquipment'
import { ResourceView } from '../shared/ResourceView'
import { RowLink } from '../shared/RowLink'
import { useResource } from '../shared/useResource'
import { RequestBadges, RequestFacts } from './RequestFacts'
import '../technicalSupport.css'

function parseFilter(value: string | null): EquipmentRequestFilter {
  return EQUIPMENT_REQUEST_FILTERS.find((filter) => filter === value) ?? 'processing'
}

/** Equipment requests by status, soonest event first. The filter lives in
 * the URL (?status=) so a filtered list can be bookmarked or linked. */
export function EquipmentRequestsPage() {
  const [params, setParams] = useSearchParams()
  const status = parseFilter(params.get('status'))
  const requests = useResource(() => fetchEquipmentRequests(status), status)

  return (
    <section className="page">
      <PageHeader title="Equipment requests" description="Review each request, reserve its equipment, then approve or reject it." />
      <div className="tech-filters">
        <div className="field">
          <label htmlFor="request-status">Status</label>
          <select
            id="request-status"
            value={status}
            onChange={(e) => setParams(e.target.value === 'processing' ? {} : { status: e.target.value }, { replace: true })}
          >
            {EQUIPMENT_REQUEST_FILTERS.map((filter) => (
              <option key={filter} value={filter}>{EQUIPMENT_REQUEST_FILTER_LABELS[filter]}</option>
            ))}
          </select>
        </div>
      </div>
      <ResourceView resource={requests} loadingText="Loading equipment requests…"
        errorText="The equipment requests could not be loaded."
        emptyText={status === 'processing' ? 'No equipment requests are awaiting review.' : 'No equipment requests match this status.'}
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="plain-list" aria-label="Equipment requests">
            {list.map((request) => (
              <li key={request.requestId}>
                <RowLink to={`${TS}/requests/${request.requestId}`} className="card tech-row-card" titleAs="h2"
                  title={request.eventName} badges={<RequestBadges request={request} />}>
                  <RequestFacts request={request} />
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </section>
  )
}
