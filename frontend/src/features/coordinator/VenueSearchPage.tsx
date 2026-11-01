import { useLiveRefresh } from '../../lib/useLiveRefresh'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Modal } from '../../components/ui/Modal'
import { PageHeader } from '../../components/ui/PageHeader'
import { RowLink } from '../../components/ui/RowLink'
import { VenueDetails } from '../../components/venue/VenueDetails'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { venueFacilityLabels, type Facility, type VenueDto } from '../../types/venue'
import { VenueFacilitiesFilter, VenueSearchField } from '../../components/venue/VenueSearchFilters'
import './VenueSearchPage.css'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'
import { occupiedPeriod } from '../../lib/bookingTiming'
import { CoordinatorVenueMap } from './CoordinatorVenueMap'
import { venueMatchesSearch } from '../../components/venue/venueMatchesSearch'
import { hasCoordinates } from '../venueMap/mapData'

function parseLocalDateTime(value: string): Date | null {
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!parts) return null

  const [, year, month, day, hour, minute] = parts.map(Number)
  const date = new Date(0)
  date.setFullYear(year, month - 1, day)
  date.setHours(hour, minute, 0, 0)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day
      || date.getHours() !== hour || date.getMinutes() !== minute) {
    return null
  }
  return date
}

export function VenueSearchPage() {
  const [searchQuery, setSearchQuery] = useState<string | null>(null)
  const liveRevision = useLiveRefresh(!!searchQuery)
  const [startDatetime, setStartDatetime] = useState('')
  const [endDatetime, setEndDatetime] = useState('')
  const [capacity, setCapacity] = useState('')
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [venues, setVenues] = useState<VenueDto[] | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  const [textQuery, setTextQuery] = useState('')
  const [mapFocusId, setMapFocusId] = useState<string>()
  const visible = useMemo(() => (venues ?? []).filter(venue => venueMatchesSearch(venue, textQuery)), [venues, textQuery])
  // The venue whose details window is open; looked up live so a refresh shows in it.
  const [detailsId, setDetailsId] = useState<string | null>(null)
  const details = visible.find(venue => venue.venueId === detailsId) ?? null

  useEffect(() => {
    if (!searchQuery || liveRevision === 0) return
    let active = true
    apiClient.get<VenueDto[]>(`/venues/search?${searchQuery}`).then(values => { if (active) { setVenues(values); setSearchError(null) } }).catch(() => { if (active) setSearchError('Could not refresh venues. Search again to retry.') })
    return () => { active = false }
  }, [searchQuery, liveRevision])

  function clearSearch() {
    setSearchQuery(null)
    setVenues(null)
    setValidationError(null)
    setSearchError(null)
    setMapFocusId(undefined)
  }

  function updateFacilities(facility: Facility, checked: boolean) {
    setFacilities(current => checked
      ? [...current, facility]
      : current.filter(value => value !== facility))
    clearSearch()
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    clearSearch()

    const start = parseLocalDateTime(startDatetime)
    const end = parseLocalDateTime(endDatetime)
    if (!start || !end) {
      setValidationError('Enter a valid start and end date and time.')
      return
    }
    if (end.getTime() <= start.getTime()) {
      setValidationError('End date and time must be after the start date and time.')
      return
    }
    if (capacity !== '' && (!/^[1-9]\d*$/.test(capacity) || Number(capacity) > 2147483647)) {
      setValidationError('Required capacity must be a whole number greater than 0.')
      return
    }

    const params = new URLSearchParams({
      startDatetime: start.toISOString(),
      endDatetime: end.toISOString(),
    })
    if (capacity !== '') params.set('capacity', capacity)
    facilities.forEach(facility => params.append('facility', facility))

    setSearching(true)
    try {
      setVenues(await apiClient.get<VenueDto[]>(`/venues/search?${params.toString()}`))
      setSearchQuery(params.toString())
    } catch (error) {
      setSearchError(error instanceof ApiClientError
        ? error.message
        : 'Could not search venues. Please try again.')
    } finally {
      setSearching(false)
    }
  }

  return (
    <section className="page page--compact venue-search venue-map-spacing">
      <PageHeader title="Search venues"
        description="Find venues that are available for the event and meet its requirements." />

      <Card>
        <form className="venue-search__form" onSubmit={search} noValidate>
          <div className="venue-search__dates">
            <VenueSearchField label="Event starts"
              type="datetime-local"
              value={startDatetime}
              data-empty={!startDatetime}
              aria-label="Event starts"
              disabled={searching}
              onChange={event => { setStartDatetime(event.target.value); clearSearch() }}
            />
            <VenueSearchField label="Event ends"
              type="datetime-local"
              value={endDatetime}
              data-empty={!endDatetime}
              aria-label="Event ends"
              disabled={searching}
              onChange={event => { setEndDatetime(event.target.value); clearSearch() }}
            />
          </div>

          <VenueSearchField label="Required capacity (optional)" className="venue-search__capacity"
            type="number"
            min="1"
            step="1"
            value={capacity}
            placeholder="e.g. 100"
            aria-label="Required capacity"
            disabled={searching}
            onChange={event => { setCapacity(event.target.value); clearSearch() }}
          />

          <VenueFacilitiesFilter selected={facilities} disabled={searching} onChange={updateFacilities} />

          <VenueSearchField label="Search by address or details" type="search" value={textQuery}
            placeholder="Venue name, address or details" onChange={event => setTextQuery(event.target.value)} />

          {validationError ? <p role="alert">{validationError}</p> : null}
          {searchError ? <p role="alert">{searchError}</p> : null}
          <Button type="submit" disabled={searching}>
            {searching ? 'Searching…' : 'Search venues'}
          </Button>
        </form>
      </Card>

      {searching ? <p role="status">Searching available venues…</p> : null}
      <CoordinatorVenueMap venues={visible} selectedId={visible.some(venue => venue.venueId === mapFocusId) ? mapFocusId : undefined}
        onSelect={setMapFocusId} />
      {venues !== null ? (
        <section className="venue-search__results" aria-labelledby="venue-search-results">
          <h2 id="venue-search-results">Search results</h2>
          {visible.length === 0 ? (
            <Card><p role="status">No matching venues found.</p></Card>
          ) : (
            <ul>
              {visible.map(venue => (
                <li key={venue.venueId}>
                  <RowLink onClick={() => setDetailsId(venue.venueId)} className="card dash-row-card"
                    title={venue.venueAddress} data-map-focused={venue.venueId === mapFocusId}>
                    <p>{venue.venueCapacity === null
                      ? 'Capacity not recorded'
                      : `${venue.venueCapacity.toLocaleString()} people`}
                      {' · '}{venue.venueFacilities.length > 0
                        ? venue.venueFacilities.map(facility => venueFacilityLabels[facility]).join(', ')
                        : 'No facilities listed'}</p>
                    {hasCoordinates(venue) ? null : <p className="dash-row-card__hint">Map location unavailable</p>}
                  </RowLink>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
      {details ? (
        <Modal title={details.venueAddress} onClose={() => setDetailsId(null)}
          footer={hasCoordinates(details) ? (
            <Button variant="secondary" onClick={() => { setMapFocusId(details.venueId); setDetailsId(null) }}>Show on map</Button>
          ) : null}>
          <VenueDetails venue={details}>
            {startDatetime && endDatetime && <BookingTimingSummary timing={{
              setupDurationMinutes: details.setupMinutes ?? 0, turnaroundDurationMinutes: details.turnaroundMinutes ?? 0,
              startDatetime: parseLocalDateTime(startDatetime)!.toISOString(), endDatetime: parseLocalDateTime(endDatetime)!.toISOString(),
              ...occupiedPeriod(parseLocalDateTime(startDatetime)!.toISOString(), parseLocalDateTime(endDatetime)!.toISOString(),
                details.setupMinutes ?? 0, details.turnaroundMinutes ?? 0), timingNote: null, conflicts: [],
            }} />}
          </VenueDetails>
        </Modal>
      ) : null}
    </section>
  )
}
