import { Link } from 'react-router-dom'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { PageHeader } from '../../components/ui/PageHeader'
import { coordinatorLeadFeatures } from './coordinatorLeadFeatures'

/** The Event Coordinator Lead's console (ECL-C1). */
export function CoordinatorLeadHomePage() {
  return (
    <div className="page">
      <PageHeader
        title="Event Coordinator Lead console"
        description="Oversee incoming event requests and decide which Event Coordinator takes each one."
        actions={
          <>
            <Link to="/coordinator-lead/unassigned-requests" className="button button--primary">
              Unassigned requests
            </Link>
            <Link to="/coordinator-lead/assignments" className="button button--secondary">
              Coordinator assignments
            </Link>
          </>
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
