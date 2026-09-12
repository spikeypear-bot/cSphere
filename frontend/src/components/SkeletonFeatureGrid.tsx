import { Link } from 'react-router-dom'
import { Card } from './ui/Card'
import type { SkeletonFeature } from '../types/skeletonFeature'
import './skeleton.css'

/** Renders one link-card per not-yet-built page, used on every role console's
 * home page (and appended to the Event Organiser's real home page for its
 * own not-yet-built stories). */
export function SkeletonFeatureGrid({
  basePath,
  features,
}: {
  basePath: string
  features: SkeletonFeature[]
}) {
  return (
    <div className="skeleton-console__grid">
      {features.map((feature) => (
        <Link key={feature.path} to={`${basePath}/${feature.path}`} className="skeleton-console-card">
          <Card className="skeleton-console-card__inner">
            <span className="feature-skeleton__badge">Skeleton</span>
            <h2>{feature.navLabel}</h2>
            <p>{feature.summary}</p>
            <div className="feature-skeleton__stories">
              {feature.storyIds.map((id) => (
                <span key={id} className="chip">
                  {id}
                </span>
              ))}
            </div>
          </Card>
        </Link>
      ))}
    </div>
  )
}
