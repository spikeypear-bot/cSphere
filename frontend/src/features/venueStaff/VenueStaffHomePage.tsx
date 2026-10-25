import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { venueStaffFeatures } from './venueStaffFeatures'
import '../../components/skeleton.css'

export function VenueStaffHomePage() {
  return <div className="page venue-staff-home">
    <PageHeader title="Venue Staff console" description="Manage venues, review booking requests and check availability." />
    <div className="skeleton-console__grid">{venueStaffFeatures.map(feature =>
      <Link key={feature.path} to={`/venue-staff/${feature.path}`} className="skeleton-console-card">
        <Card className="skeleton-console-card__inner"><h2>{feature.navLabel}</h2><p>{feature.summary}</p></Card>
      </Link>)}
    </div>
  </div>
}
