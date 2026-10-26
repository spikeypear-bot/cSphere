import { Navigate, useLocation, useParams } from 'react-router-dom'
import { reportPath, TS } from './shared/faultyEquipment'

// The console's routes before the side-navigation rework. Old links (and
// bookmarks) keep working: each lands on the page that replaced it, with its
// query string kept.

/** /availability and /reservations: the requests list, or with ?request=ID
 * (a link to one request) that request's own page. */
export function LegacyRequestRedirect() {
  const { search, hash } = useLocation()
  const params = new URLSearchParams(search)
  const requestId = params.get('request')
  params.delete('request')
  const rest = params.toString()
  return (
    <Navigate
      replace
      to={{
        pathname: requestId ? `${TS}/requests/${encodeURIComponent(requestId)}` : `${TS}/requests`,
        search: rest ? `?${rest}` : '',
        hash,
      }}
    />
  )
}

/** /status, /damaged-equipment and /damaged-equipment/new: a fixed new page. */
export function LegacyRedirect({ to }: { to: string }) {
  const { search, hash } = useLocation()
  return <Navigate replace to={{ pathname: to, search, hash }} />
}

/** /damaged-equipment/:reportId: the report under Faulty equipment. */
export function LegacyReportRedirect() {
  const { reportId = '' } = useParams<{ reportId: string }>()
  const { search, hash } = useLocation()
  return <Navigate replace to={{ pathname: reportPath(encodeURIComponent(reportId)), search, hash }} />
}
