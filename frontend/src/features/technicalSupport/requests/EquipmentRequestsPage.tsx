import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../../components/ui/PageHeader'
import {
  EQUIPMENT_REQUEST_FILTER_LABELS,
  type EquipmentRequestFilter,
} from '../reservations/reservation.types'
import { fetchEquipmentRequests } from '../reservations/reservationApi'
import { TS } from '../shared/faultyEquipment'
import { ResourceView } from '../../../components/ui/ResourceView'
import { RowLink } from '../../../components/ui/RowLink'
import { useRefreshingResource } from '../../../lib/useRefreshingResource'
import { RequestBadges, RequestFacts } from './RequestFacts'
import '../technicalSupport.css'
import '../../../components/ui/JourneyStatus.css'
import './EquipmentRequestsPage.css'

// Read every status together so navigation counts stay consistent across filters.
const NAV_FILTERS: EquipmentRequestFilter[] = ['processing', 'approved', 'attention', 'rejected', 'superseded', 'all']
const NAV_LABELS = { ...EQUIPMENT_REQUEST_FILTER_LABELS, attention: 'Needs reassessment' }

function parseFilter(value: string | null): EquipmentRequestFilter {
  return NAV_FILTERS.find((filter) => filter === value) ?? 'processing'
}

/** Equipment requests by status, soonest event first. The filter lives in
 * the URL (?status=) so a filtered list can be bookmarked or linked. */
export function EquipmentRequestsPage() {
  const [params, setParams] = useSearchParams()
  const status = parseFilter(params.get('status'))
  const requests = useRefreshingResource(() => fetchEquipmentRequests('all'), 'equipment-requests')
  const matchesStatus = (requestStatus: EquipmentRequestFilter, filter: EquipmentRequestFilter) =>
    filter === 'all' || requestStatus === filter || (filter === 'approved' && requestStatus === 'attention')
  const visible = requests.data?.filter(request => matchesStatus(request.status, status)) ?? null
  const filtered = { ...requests, data: visible }

  function selectStatus(next: EquipmentRequestFilter) {
    const updated = new URLSearchParams(params)
    if (next === 'processing') updated.delete('status')
    else updated.set('status', next)
    setParams(updated, { replace: true })
  }

  function countFor(filter: EquipmentRequestFilter) {
    return requests.data?.filter(request => matchesStatus(request.status, filter)).length ?? null
  }

  return (
    <section className="page equipment-requests-page">
      <PageHeader title="Equipment requests" description="Review each request, reserve its equipment, then approve or reject it." />
      <div className="status-filter" role="group" aria-label="Filter equipment requests by status">
        {NAV_FILTERS.map(filter => <button key={filter} type="button" className="chip"
          aria-pressed={status === filter} onClick={() => selectStatus(filter)}>
          {NAV_LABELS[filter]}
          {countFor(filter) !== null ? <>{' '}<span className="status-filter__count">{countFor(filter)}</span></> : null}
        </button>)}
      </div>
      {status === 'approved' ? <p className="field-hint">Includes approved requests awaiting coordinator reassessment.</p> : null}
      <ResourceView resource={filtered} loadingText="Loading equipment requests…"
        errorText="The equipment requests could not be loaded."
        emptyText={status === 'processing' ? 'No equipment requests are awaiting review.' : 'No equipment requests match this status.'}
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="plain-list" aria-label="Equipment requests">
            {list.map((request) => (
              <li key={request.requestId}>
                <RowLink to={`${TS}/requests/${request.requestId}`} className="card dash-row-card" titleAs="h2"
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
