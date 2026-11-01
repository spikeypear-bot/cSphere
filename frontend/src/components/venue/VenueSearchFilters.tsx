import type { InputHTMLAttributes } from 'react'
import { venueFacilities, venueFacilityLabels, type Facility } from '../../types/venue'
import './VenueSearchFilters.css'

/** Venue-discovery inputs shared by coordinator search and the staff map. */
export function VenueSearchField({ label, className, ...input }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return <label className={`venue-search-field${className ? ` ${className}` : ''}`}>
    <span>{label}</span>
    <input {...input} />
  </label>
}

export function VenueFacilitiesFilter({ selected, onChange, disabled = false, legend = 'Required facilities (optional)' }: {
  selected: Facility[]
  onChange: (facility: Facility, checked: boolean) => void
  disabled?: boolean
  legend?: string
}) {
  return <fieldset className="venue-facility-filter" disabled={disabled}>
    <legend>{legend}</legend>
    {venueFacilities.map(facility => <label key={facility}>
      <input type="checkbox" checked={selected.includes(facility)} disabled={disabled}
        onChange={event => onChange(facility, event.target.checked)} />
      {venueFacilityLabels[facility]}
    </label>)}
  </fieldset>
}
