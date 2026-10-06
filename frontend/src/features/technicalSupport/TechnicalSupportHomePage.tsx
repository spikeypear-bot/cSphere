import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { SkeletonFeatureGrid } from '../../components/SkeletonFeatureGrid'
import { technicalSupportFeatures } from './technicalSupportFeatures'

export function TechnicalSupportHomePage() {
  return (
    <div className="page">
      <div className="skeleton-console__header">
        <h1>Technical Support Staff console</h1>
        <p className="field-hint">Check equipment availability, reserve items for events, and update operational status.</p>
      </div>
      <div className="skeleton-console__grid">
        <Link to="/technical-support/reservations" className="skeleton-console-card">
          <Card className="skeleton-console-card__inner">
            <span className="feature-skeleton__badge">Available</span>
            <h2>Reservations</h2>
            <p>Select an event that needs equipment, check availability, and save or review its reservations.</p>
            <span className="chip">TS02</span>
          </Card>
        </Link>
        <Link to="/technical-support/status" className="skeleton-console-card">
          <Card className="skeleton-console-card__inner">
            <span className="feature-skeleton__badge">Available</span>
            <h2>Equipment status</h2>
            <p>View and update equipment status for a date and time period.</p>
            <span className="chip">TS03</span>
          </Card>
        </Link>
      </div>
      <div>
        <h2>More Technical Support stories</h2>
        <p className="field-hint">This page is not built yet.</p>
        <SkeletonFeatureGrid
          basePath="/technical-support"
          features={technicalSupportFeatures.filter(feature => feature.path === 'availability')}
        />
      </div>
    </div>
  )
}
