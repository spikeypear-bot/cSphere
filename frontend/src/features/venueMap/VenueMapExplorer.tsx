import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { PageHeader } from '../../components/ui/PageHeader'
import type { Facility, VenueDto } from '../../types/venue'
import { VenueFacilitiesFilter, VenueSearchField } from '../../components/venue/VenueSearchFilters'
import { useVenueRead } from '../venueStaff/useVenueRead'
import { VenueMapDetails } from './VenueMapDetails'
import { VenueMapPanel } from './VenueMapPanel'
import { filterVenues, hasCoordinates, venueName, type VenueMapActivity } from './mapData'
import './VenueMapExplorer.css'

const EMPTY_ACTIVITY: VenueMapActivity[] = []

export function VenueMapExplorer() {
  const [query, setQuery] = useState('')
  const [capacity, setCapacity] = useState('')
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [date, setDate] = useState('')
  const [selectedId, setSelectedId] = useState<string>()
  const [mobileView, setMobileView] = useState<'list' | 'map'>('list')
  const list = useRef<HTMLUListElement>(null)
  const venues = useVenueRead<VenueDto[]>('/venues', false, 30000, true, false)
  const activity = useVenueRead<VenueMapActivity[]>(`/venue-staff/map/activity${date ? `?date=${date}` : ''}`, false, 30000, true, false)
  const capacityInvalid = capacity !== '' && (!/^[1-9]\d*$/.test(capacity) || Number(capacity) > 50000)
  const filtered = useMemo(() => filterVenues(venues.data ?? [], query, capacityInvalid ? '' : capacity,
    facilities, date, activity.data ?? EMPTY_ACTIVITY), [venues.data, query, capacityInvalid, capacity, facilities, date, activity.data])
  const selected = filtered.find(v => v.venueId === selectedId)
  const activeId = selected?.venueId
  useEffect(() => {
    if (!activeId) return
    // The catalogue is always rendered; effects run after its ref is committed.
    const catalogue = list.current!
    const card = Array.from(catalogue.querySelectorAll<HTMLButtonElement>('[data-venue-id]'))
      .find(button => button.dataset.venueId === activeId)
    if (card) {
      // Scroll only the catalogue, so marker selection never moves the whole console.
      catalogue.scrollTop += card.getBoundingClientRect().top - catalogue.getBoundingClientRect().top - 8
    }
  }, [activeId])
  function select(id: string) { setSelectedId(id) }
  function clear() { setQuery(''); setCapacity(''); setFacilities([]); setDate(''); setSelectedId(undefined) }
  function refresh() { venues.retry(); activity.retry() }
  const ready = !!venues.data && !!activity.data
  const failed = venues.error || activity.error
  const results = ready && !failed && !capacityInvalid ? filtered : []
  return <section className="venue-map-explorer venue-map-spacing" aria-labelledby="explore-map-heading">
    <PageHeader title="Explore venues" headingLevel={2} headingId="explore-map-heading"
      description="Find a venue and review its upcoming events and bookings."
      actions={<Button className="venue-refresh-button" variant="ghost" onClick={refresh} aria-label="Refresh venues"><RefreshIcon /> Refresh</Button>} />
    <Card className="feature-skeleton__body venue-map-filters">
      <VenueSearchField label="Search venues" type="search" value={query} placeholder="Venue name or address"
        onChange={e => setQuery(e.target.value)} />
      <div className="venue-map-filter-row">
        <VenueSearchField label="Minimum capacity" type="number" min="1" max="50000" step="1" value={capacity} placeholder="e.g. 100"
          aria-invalid={capacityInvalid} onChange={e => setCapacity(e.target.value)} />
        <VenueSearchField label="Events on date (SGT)" type="date" value={date} data-empty={!date} onChange={e => setDate(e.target.value)} />
      </div>
      {capacityInvalid && <p role="alert">Minimum capacity must be a whole number from 1 to 50,000.</p>}
      <VenueFacilitiesFilter legend="Required facilities" selected={facilities}
        onChange={(facility, checked) => setFacilities(current => checked
          ? [...current, facility] : current.filter(f => f !== facility))} />
      <Button className="venue-map-clear" variant="secondary" onClick={clear}>Clear filters</Button>
      <p className="field-hint">Choose a date to see scheduled bookings and requests. Check the availability calendar before confirming a new booking.</p>
    </Card>
    <div className="venue-map-mobile-switch" role="group" aria-label="Venue display">
      <Button variant={mobileView === 'list' ? 'primary' : 'secondary'} aria-pressed={mobileView === 'list'} onClick={() => setMobileView('list')}>List view</Button>
      <Button variant={mobileView === 'map' ? 'primary' : 'secondary'} aria-pressed={mobileView === 'map'} onClick={() => setMobileView('map')}>Map view</Button>
    </div>
    {failed ? <Card role="alert" className="feature-skeleton__body venue-map-state"><p>{failed}</p><Button onClick={refresh}>Try again</Button></Card>
      : !ready ? <p role="status">Loading venues…</p> : null}
    {ready && !failed && <p role="status" className="field-hint">{results.length} {results.length === 1 ? 'venue' : 'venues'} found · Approved events{date ? ' on the selected date' : ' coming up or in progress'}.</p>}
    <div className="venue-map-split" data-mobile-view={mobileView}>
      <div className="venue-map-catalogue">
        <ul className="venue-map-list" ref={list} aria-label="Map venue catalogue">
          {results.map(venue => {
            const count = activity.data?.find(a => a.venueId === venue.venueId)
            return <li key={venue.venueId}><button type="button" className="venue-map-card" data-venue-id={venue.venueId}
              aria-pressed={activeId === venue.venueId} onClick={() => select(venue.venueId)}>
              <strong>{venueName(venue)}</strong><span>{venue.venueAddress}</span>
              <span>{venue.venueCapacity == null ? 'Capacity not recorded' : `${venue.venueCapacity.toLocaleString()} people`} · {count?.eventCount ?? 0} approved {(count?.eventCount ?? 0) === 1 ? 'event' : 'events'}</span>
              {(count?.pendingBookingCount ?? 0) > 0 && <span>{count!.pendingBookingCount} pending booking {(count!.pendingBookingCount === 1) ? 'request' : 'requests'}</span>}
              {!hasCoordinates(venue) && <span className="venue-map-location-missing">Map location unavailable</span>}
            </button></li>
          })}
        </ul>
        {ready && !failed && results.length === 0 && <Card className="feature-skeleton__body venue-map-state"><h3>{venues.data?.length === 0 ? 'No venues yet' : 'No matching venues'}</h3><p>Try another search or clear the filters.</p></Card>}
      </div>
      <div className="venue-map-map-panel">
        <VenueMapPanel venues={results} selectedId={activeId} onSelect={select} />
      </div>
    </div>
    {selected && ready && !failed && !capacityInvalid && <VenueMapDetails key={`${selected.venueId}:${date}`} venue={selected} date={date} onClose={() => setSelectedId(undefined)} />}
  </section>
}
