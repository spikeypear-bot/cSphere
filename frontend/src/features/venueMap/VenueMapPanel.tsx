import { lazy, Suspense } from 'react'
import { Card } from '../../components/ui/Card'
import type { VenueMapProps } from './VenueMap'
import './VenueMap.css'

const VenueMap = lazy(() => import('./VenueMap'))

/** Shared lazy boundary keeps Leaflet out of the main application bundle. */
export function VenueMapPanel(props: VenueMapProps) {
  return <Suspense fallback={<Card className="feature-skeleton__body venue-map-state" role="status">Loading map…</Card>}>
    <VenueMap {...props} />
  </Suspense>
}
