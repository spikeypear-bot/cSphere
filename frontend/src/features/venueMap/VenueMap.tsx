import { useEffect, useMemo, useRef, useState } from 'react'
import { divIcon, type Marker as LeafletMarker } from 'leaflet'
import { MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import type { VenueDto } from '../../types/venue'
import { groupVenues, hasCoordinates, venueName } from './mapData'

const SINGAPORE: [number, number] = [1.3521, 103.8198]
const TILE_URL = import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'

function MapViewport({ selected, reset }: { selected?: VenueDto; reset: number }) {
  const map = useMap()
  const located = selected && hasCoordinates(selected) ? selected : undefined
  const latitude = located?.latitude
  const longitude = located?.longitude
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => map.invalidateSize({ animate: false, debounceMoveend: true }))
    observer.observe(map.getContainer())
    return () => observer.disconnect()
  }, [map])
  useEffect(() => {
    if (typeof latitude === 'number' && typeof longitude === 'number') map.setView([latitude, longitude], Math.max(map.getZoom(), 15))
  }, [map, latitude, longitude])
  useEffect(() => { if (reset > 0) map.setView(SINGAPORE, 11) }, [map, reset])
  return null
}

function VenueMarker({ group, selectedId, onSelect }: {
  group: ReturnType<typeof groupVenues>[number]; selectedId?: string; onSelect: (id: string) => void
}) {
  const marker = useRef<LeafletMarker>(null)
  const selected = group.venues.some(v => v.venueId === selectedId)
  const icon = useMemo(() => divIcon({
    className: `venue-map-marker${selected ? ' venue-map-marker--selected' : ''}`,
    html: `<span>${group.venues.length}</span>`,
    iconSize: [34, 34], iconAnchor: [17, 17], popupAnchor: [0, -20],
  }), [group.venues.length, selected])
  useEffect(() => { if (selected) marker.current?.openPopup() }, [selected, selectedId])
  const label = group.venues.length === 1 ? venueName(group.venues[0]) : `${group.venues.length} venues near ${group.venues[0].venueAddress}`
  return <Marker ref={marker} position={group.position} icon={icon} title={label} alt={label}
    eventHandlers={{
      click: () => onSelect(selectedId && selected ? selectedId : group.venues[0].venueId),
      keydown: event => {
        if (event.originalEvent.key === 'Enter' || event.originalEvent.key === ' ') {
          event.originalEvent.preventDefault()
          onSelect(selectedId && selected ? selectedId : group.venues[0].venueId)
          marker.current?.openPopup()
        }
      },
    }}>
    <Popup autoPan={false}>
      <div className="venue-map-popup">
        {group.venues.length > 1 && <strong>Choose a venue at this location</strong>}
        {group.venues.map(venue => <button type="button" key={venue.venueId}
          aria-pressed={venue.venueId === selectedId} onClick={() => onSelect(venue.venueId)}>{venueName(venue)}</button>)}
      </div>
    </Popup>
  </Marker>
}

/** Presentation only: role permissions and data loading belong to the caller. */
export default function VenueMap({ venues, selectedId, onSelect }: {
  venues: VenueDto[]; selectedId?: string; onSelect: (id: string) => void
}) {
  const groups = useMemo(() => groupVenues(venues), [venues])
  const [reset, setReset] = useState(0)
  const [tileFailed, setTileFailed] = useState(false)
  const [tileRevision, setTileRevision] = useState(0)
  return <Card className="venue-map-surface" role="region" aria-label="Interactive venue map">
    <div className="venue-map-toolbar">
      <span>{venues.filter(hasCoordinates).length} {venues.filter(hasCoordinates).length === 1 ? 'venue' : 'venues'} on map</span>
      <Button variant="ghost" onClick={() => setReset(n => n + 1)}>Reset map</Button>
    </div>
    {tileFailed && <div className="venue-map-tile-error" role="status">Map tiles could not load. You can still browse and select venues.
      <Button variant="ghost" onClick={() => { setTileFailed(false); setTileRevision(n => n + 1) }}>Retry map tiles</Button>
    </div>}
    <MapContainer center={SINGAPORE} zoom={11} scrollWheelZoom touchZoom className="venue-map-canvas">
      <TileLayer key={tileRevision} url={TILE_URL} attribution={ATTRIBUTION}
        eventHandlers={{ tileerror: () => setTileFailed(true) }} />
      <MapViewport selected={venues.find(v => v.venueId === selectedId)} reset={reset} />
      {groups.map(group => <VenueMarker key={group.venues[0].venueId} group={group}
        selectedId={selectedId} onSelect={onSelect} />)}
    </MapContainer>
    <p className="field-hint venue-map-caption">Select a location to view its venues. <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noreferrer">Report a map issue</a></p>
  </Card>
}
