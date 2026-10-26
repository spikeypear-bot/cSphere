import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { TextField, NumberField } from '../../components/ui/fields'
import { ChipGroup } from '../../components/ui/ChipGroup'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { venueLayouts, venueLayoutLabels, venueAccessibilities, venueAccessibilityLabels,
  venueFacilities, venueFacilityLabels, type CreateVenueDto, type VenueDto } from '../../types/venue'
import '../../components/skeleton.css'
import './VenueForm.css'
import { validMinutes } from '../../lib/bookingTiming'

export function VenueCreatePage({ initialVenue }: { initialVenue?: VenueDto }) {
  const navigate = useNavigate()
  const busy = useRef(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [form, setForm] = useState<CreateVenueDto>(initialVenue ? { ...initialVenue, setupMinutes: initialVenue.setupMinutes ?? 0, turnaroundMinutes: initialVenue.turnaroundMinutes ?? 0 } : { venueName: '', venueAddress: '', venueCapacity: 50,
    supportedLayouts: [], venueAccessibilities: [], venueFacilities: [], operatingInformation: '', additionalInformation: '',
    operatingHours: [], setupMinutes: 0, turnaroundMinutes: 0 })
  function change<K extends keyof CreateVenueDto>(key: K, value: CreateVenueDto[K]) {
    setForm(previous => ({ ...previous, [key]: value }))
    let message = ''
    if (key === 'venueCapacity' && value !== null &&
        (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 50000))
      message = 'Enter a whole number from 1 to 50,000.'
    if (key === 'venueAddress' && typeof value === 'string' && value.trim().length > 500)
      message = 'Use 500 characters or fewer.'
    setErrors(previous => ({ ...previous, [key]: message }))
    setError('')
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy.current) return
    const next: Record<string, string> = {}
    if (!validMinutes(form.setupMinutes)) next.setupMinutes = 'Enter whole minutes from 0 to 10,080.'
    if (!validMinutes(form.turnaroundMinutes)) next.turnaroundMinutes = 'Enter whole minutes from 0 to 10,080.'
    if (!form.venueAddress.trim()) next.venueAddress = 'Enter the venue address.'
    else if (form.venueAddress.trim().length > 500) next.venueAddress = 'Use 500 characters or fewer.'
    if (!initialVenue && !form.venueName?.trim()) next.venueName = 'Enter the venue name.'
    else if (form.venueName && form.venueName.trim().length > 200) next.venueName = 'Use 200 characters or fewer.'
    if (form.venueCapacity === null || !Number.isInteger(form.venueCapacity) || form.venueCapacity < 1 || form.venueCapacity > 50000)
      next.venueCapacity = 'Enter a whole number from 1 to 50,000.'
    if (!form.supportedLayouts.length) next.supportedLayouts = 'Select at least one layout.'
    if (!form.operatingInformation.trim()) next.operatingInformation = 'Enter operating days and hours.'
    setErrors(next)
    setError('')
    if (Object.keys(next).length) return
    busy.current = true
    setSaving(true)
    try {
      if (initialVenue) {
        const changes: Partial<CreateVenueDto> = {}
        const fields = ['venueName', 'venueCapacity', 'supportedLayouts', 'venueAccessibilities', 'venueFacilities', 'operatingInformation', 'additionalInformation', 'operatingHours', 'setupMinutes', 'turnaroundMinutes'] as const
        for (const field of fields) {
          const original = field === 'setupMinutes' || field === 'turnaroundMinutes' ? initialVenue[field] ?? 0 : initialVenue[field]
          if (JSON.stringify(form[field]) !== JSON.stringify(original)) {
            Object.assign(changes, { [field]: form[field] })
          }
        }
        await apiClient.put<VenueDto>(`/venues/${initialVenue.venueId}`, changes)
      } else {
        await apiClient.post<VenueDto>('/venues', { ...form, venueName: form.venueName?.trim(), venueAddress: form.venueAddress.trim(),
          operatingInformation: form.operatingInformation.trim(), additionalInformation: form.additionalInformation?.trim() || null })
      }
      navigate('/venue-staff/catalogue', { replace: true, state: { venueSaved: true, venueUpdated: !!initialVenue } })
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not save the venue. Please try again.')
    } finally { busy.current = false; setSaving(false) }
  }
  return <div className="feature-skeleton venue-form">
    <Link to="/venue-staff/catalogue">← Venue catalogue</Link>
    <PageHeader title={initialVenue ? 'Edit Venue' : 'Add Venue'}
      description="Record capacity, supported layouts, accessibility provisions, facilities and operating information. All fields are required unless marked optional." />
    <Card className="feature-skeleton__body"><form className="feature-skeleton__detail-preview" onSubmit={save} noValidate aria-busy={saving}>
      <TextField id="venueName" label="Venue name" placeholder="e.g. Marina Bay Hall" value={form.venueName ?? ''} onChange={v => change('venueName', v)} error={errors.venueName} hint="Public display name shown to attendees. Maximum 200 characters." />
      {initialVenue ? <div className="field"><strong>Venue address</strong><p>{form.venueAddress || 'Not recorded'}</p></div> : <TextField id="venueAddress" label="Venue address" placeholder="e.g. 10 Bay Road, Singapore" multiline value={form.venueAddress} onChange={v => change('venueAddress', v)} error={errors.venueAddress} hint="Full location/address for attendees. Maximum 500 characters." />}
      <NumberField id="venueCapacity" label="Overall capacity" value={form.venueCapacity} min={1} onChange={v => change('venueCapacity', v)} error={errors.venueCapacity} hint="1–50,000 people. One capacity applies to every selected layout." />
      <NumberField id="setupMinutes" label="Default setup (minutes)" value={form.setupMinutes ?? null} min={0} onChange={v => change('setupMinutes', v)} error={errors.setupMinutes} hint="Whole minutes, 0–10,080, before the event." />
      <NumberField id="turnaroundMinutes" label="Default turnaround (minutes)" value={form.turnaroundMinutes ?? null} min={0} onChange={v => change('turnaroundMinutes', v)} error={errors.turnaroundMinutes} hint="Whole minutes, 0–10,080, after the event. Defaults apply to new requests; existing booking timings are preserved." />
      <div className="field"><ChipGroup label="Supported layouts" options={venueLayouts} labels={venueLayoutLabels} selected={form.supportedLayouts} onChange={v => change('supportedLayouts', v)} />
        <span className="field-hint">Select the arrangements this room or space supports.</span>
        {errors.supportedLayouts && <span className="field-error" role="alert">{errors.supportedLayouts}</span>}</div>
      <div className="field">
        <ChipGroup label="Accessibility provisions (optional)" options={venueAccessibilities} labels={venueAccessibilityLabels}
          selected={form.venueAccessibilities} onChange={values => change('venueAccessibilities',
            values.includes('none') && !form.venueAccessibilities.includes('none')
              ? ['none'] : values.filter(value => value !== 'none'))} />
        <span className="field-hint">Select the provisions present, or choose No accessibility provisions. Leave blank if not recorded.</span>
      </div>
      <ChipGroup label="Facilities (optional)" options={venueFacilities} labels={venueFacilityLabels}
        selected={form.venueFacilities} onChange={values => change('venueFacilities', values)} />
      <TextField id="operatingInformation" label="Operating information" placeholder="e.g. Monday–Friday, 09:00–18:00. Closed on public holidays." multiline value={form.operatingInformation} onChange={v => change('operatingInformation', v)} error={errors.operatingInformation} hint="Include operating days, hours and any closures." />
      <fieldset className="venue-hours">
        <legend>Structured operating hours</legend>
        <p className="field-hint">Bookings are only allowed when the complete event period fits one configured daily interval.</p>
        {['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((day, index) => {
          const current = (form.operatingHours ?? []).find(hour => hour.dayOfWeek === index + 1)
          return <div className="venue-hours__row" key={day}>
            <label><input type="checkbox" checked={Boolean(current)} onChange={e => change('operatingHours',
              e.target.checked ? [...(form.operatingHours ?? []), { dayOfWeek: index + 1, openTime: '09:00', closeTime: '18:00' }]
                : (form.operatingHours ?? []).filter(hour => hour.dayOfWeek !== index + 1))} />{day}</label>
            {current ? <>
              <input aria-label={`${day} opens`} type="time" value={current.openTime} onChange={e => change('operatingHours',
                (form.operatingHours ?? []).map(hour => hour.dayOfWeek === index + 1 ? { ...hour, openTime: e.target.value } : hour))} />
              <input aria-label={`${day} closes`} type="time" value={current.closeTime} onChange={e => change('operatingHours',
                (form.operatingHours ?? []).map(hour => hour.dayOfWeek === index + 1 ? { ...hour, closeTime: e.target.value } : hour))} />
            </> : <span className="field-hint">Closed</span>}
          </div>
        })}
        {errors.operatingHours && <span className="field-error" role="alert">{errors.operatingHours}</span>}
      </fieldset>
      <TextField id="additionalInformation" label="Additional information (optional)" placeholder="e.g. Use the entrance on Level 2." multiline value={form.additionalInformation ?? ''} onChange={v => change('additionalInformation', v)} />
      {error && <div className="field"><p className="field-error" role="alert">{error}</p></div>}
      <div className="feature-skeleton__actions"><Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save venue'}</Button>
        {!saving && <Link className="button button--secondary" to="/venue-staff/catalogue">Cancel</Link>}</div>
    </form></Card>
  </div>
}
