import { Link } from 'react-router-dom'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { coordinatorFeatures } from './coordinatorFeatures'
import './CoordinatorHomePage.css'

/** EC01/EC02's real entry point — mirrors OrganiserHomePage's own "New event
 * request" button sitting above its skeleton grid, rather than a "Skeleton"
 * card for a page that now actually exists. */
export function CoordinatorHomePage() {
  return (
    <div className="coordinator-home">
      <div className="coordinator-home__header">
        <div>
          <h1>Event Coordinator console</h1>
          <p className="field-hint">Review submitted event requests and manage assignments.</p>
        </div>
        <Link to="/coordinator/review-queue" className="button button--primary">
          Review requests
        </Link>
      </div>

      <div className="coordinator-home__more">
        <h2>More Event Coordinator stories</h2>
        <p className="field-hint">Not built yet — pick one up next sprint.</p>
        <SkeletonFeatureGrid basePath="/coordinator" features={coordinatorFeatures} />
      </div>
    </div>
  )
}
