import { Link } from 'react-router-dom'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { PageHeader } from '../../components/ui/PageHeader'
import { coordinatorLeadFeatures } from './coordinatorLeadFeatures'

/** The Event Coordinator Lead's console (Week 7 customer change 5). Laid out
 * like CoordinatorHomePage: real pages are buttons in the header, stories
 * nobody has built yet are skeleton cards underneath. */
export function CoordinatorLeadHomePage() {
  return (
    <div className="page">
      <PageHeader
        title="Event Coordinator Lead console"
        description="Oversee incoming event requests and decide which Event Coordinator takes each one."
        actions={
          <Link to="/coordinator-lead/unassigned-requests" className="button button--primary">
            Unassigned requests
          </Link>
        }
      />

      <div className="page-section--divided">
        <h2>More Event Coordinator Lead stories</h2>
        <p className="field-hint">Not built yet — pick one up next.</p>
        <SkeletonFeatureGrid basePath="/coordinator-lead" features={coordinatorLeadFeatures} />
      </div>
    </div>
  )
}
