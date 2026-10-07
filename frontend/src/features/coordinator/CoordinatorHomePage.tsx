import { Link } from 'react-router-dom'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { coordinatorFeatures } from './coordinatorFeatures'
import { PageHeader } from '../../components/ui/PageHeader'

/** EC01/EC02's real entry point — mirrors OrganiserHomePage's own "New event
 * request" button sitting above its skeleton grid, rather than a "Skeleton"
 * card for a page that now actually exists. */
export function CoordinatorHomePage() {
  return (
    <div className="page">
      <PageHeader
        title="Event Coordinator console"
        description="Review submitted event requests and manage assignments."
        actions={
          <>
            <Link to="/coordinator/review-queue" className="button button--primary">
              Review requests
            </Link>
            <Link to="/coordinator/my-events" className="button button--secondary">
              My events
            </Link>
            <Link to="/coordinator/operational-issues" className="button button--secondary">
              Venue issues
            </Link>
            <Link to="/coordinator/venue-search" className="button button--secondary">
              Search venues
            </Link>
          </>
        }
      />

      <div className="page-section--divided">
        <h2>More Event Coordinator stories</h2>
        <p className="field-hint">Not built yet — pick one up next sprint.</p>
        <SkeletonFeatureGrid basePath="/coordinator" features={coordinatorFeatures} />
      </div>
    </div>
  )
}
