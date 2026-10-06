import { useState, type FormEvent } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { venueFacilities, venueFacilityLabels, type Facility, type VenueDto } from '../../types/venue'
import './VenueSearchPage.css'

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
  const [startDatetime, setStartDatetime] = useState('')
  const [endDatetime, setEndDatetime] = useState('')
  const [capacity, setCapacity] = useState('')
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [venues, setVenues] = useState<VenueDto[] | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)

  function clearSearch() {
    setVenues(null)
    setValidationError(null)
    setSearchError(null)
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
    } catch (error) {
      setSearchError(error instanceof ApiClientError
        ? error.message
        : 'Could not search venues. Please try again.')
    } finally {
      setSearching(false)
    }
  }

  return (
    <section className="page page--compact venue-search">
      <PageHeader title="Search venues"
        description="Find venues that are available for the event and meet its requirements." />

      <Card>
        <form className="venue-search__form" onSubmit={search} noValidate>
          <div className="venue-search__dates">
            <label>
              <span>Event starts</span>
              <input
                type="datetime-local"
                value={startDatetime}
                aria-label="Event starts"
                disabled={searching}
                onChange={event => { setStartDatetime(event.target.value); clearSearch() }}
              />
            </label>
            <label>
              <span>Event ends</span>
              <input
                type="datetime-local"
                value={endDatetime}
                aria-label="Event ends"
                disabled={searching}
                onChange={event => { setEndDatetime(event.target.value); clearSearch() }}
              />
            </label>
          </div>

          <label className="venue-search__capacity">
            <span>Required capacity (optional)</span>
            <input
              type="number"
              min="1"
              step="1"
              value={capacity}
              aria-label="Required capacity"
              disabled={searching}
              onChange={event => { setCapacity(event.target.value); clearSearch() }}
            />
          </label>

          <fieldset className="venue-search__facilities">
            <legend>Required facilities (optional)</legend>
            {venueFacilities.map(facility => (
              <label key={facility}>
                <input
                  type="checkbox"
                  checked={facilities.includes(facility)}
                  disabled={searching}
                  onChange={event => updateFacilities(facility, event.target.checked)}
                />
                {venueFacilityLabels[facility]}
              </label>
            ))}
          </fieldset>

          {validationError ? <p role="alert">{validationError}</p> : null}
          {searchError ? <p role="alert">{searchError}</p> : null}
          <Button type="submit" disabled={searching}>
            {searching ? 'Searching…' : 'Search venues'}
          </Button>
        </form>
      </Card>

      {searching ? <p role="status">Searching available venues…</p> : null}
      {venues !== null ? (
        <section className="venue-search__results" aria-labelledby="venue-search-results">
          <h2 id="venue-search-results">Search results</h2>
          {venues.length === 0 ? (
            <Card><p role="status">No matching venues found.</p></Card>
          ) : (
            <ul>
              {venues.map(venue => (
                <li key={venue.venueId}>
                  <Card>
                    <h3>{venue.venueAddress}</h3>
                    <p>{venue.venueCapacity === null
                      ? 'Capacity not recorded'
                      : `${venue.venueCapacity.toLocaleString()} people capacity`}</p>
                    <p>{venue.venueFacilities.length > 0
                      ? venue.venueFacilities.map(facility => venueFacilityLabels[facility]).join(', ')
                      : 'No facilities listed'}</p>
                    <p>{venue.operatingInformation}</p>
                  </Card>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </section>
  )
}
