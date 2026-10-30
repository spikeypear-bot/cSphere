import { useEffect, useState, type FormEvent } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient } from '../../lib/apiClient'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './OrganiserRegistrations.css'
import type { EventStatus, RegistrationInfoDto, RegistrationState, RegistrationStatus } from '../../types/event'

const STATE_LABELS: Record<RegistrationState, string> = {
  not_required: 'Not required',
  not_yet_open: 'Not yet open',
  open: 'Open',
  closed: 'Closed',
  full: 'Full',
}

const STATUS_LABELS: Record<RegistrationStatus, string> = {
  confirmed: 'Active (confirmed)',
  withdrawn: 'Withdrawn',
  event_cancelled: 'Affected by event cancellation',
}

interface OrganiserRegistrationsProps {
  eventId: string
  eventStartDatetime?: string
  eventEndDatetime?: string
  eventStatus?: EventStatus
}

/** EO10/EO12: the organiser's registration information and period editor. */
export function OrganiserRegistrations({
  eventId, eventStartDatetime, eventEndDatetime, eventStatus,
}: OrganiserRegistrationsProps) {
  const [data, setData] = useState<RegistrationInfoDto | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [periodDraft, setPeriodDraft] = useState<{ opensAt: string; closesAt: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [saveSuccess, setSaveSuccess] = useState('')
  const [now, setNow] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    apiClient.get<RegistrationInfoDto>(`/events/${eventId}/registrations`)
      .then((value) => {
        if (cancelled) return
        if (value && typeof value.registrationState === 'string') setData(value)
        else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [eventId, attempt])

  useEffect(() => {
    let timer: number
    const updateTime = () => {
      setNow(Date.now())
      timer = window.setTimeout(updateTime, 30_000)
    }
    timer = window.setTimeout(updateTime, 0)
    return () => window.clearTimeout(timer)
  }, [])

  if (failed) {
    return (
      <Card role="region" aria-labelledby="registrations-heading">
        <h2 id="registrations-heading">Registration information</h2>
        <p role="alert">We could not load registration information. Check your connection and try again.</p>
        <Button variant="secondary" onClick={() => { setFailed(false); setAttempt((n) => n + 1) }}>Try again</Button>
      </Card>
    )
  }
  if (!data) return null

  const savedRegistration = data
  const pct = data.capacity ? Math.min(100, Math.round((data.confirmedCount / data.capacity) * 100)) : 0
  const opensAtInput = periodDraft?.opensAt ?? toSingaporeInput(data.opensAt)
  const closesAtInput = periodDraft?.closesAt ?? toSingaporeInput(data.closesAt)
  const hasEventDetails = eventStartDatetime !== undefined && eventEndDatetime !== undefined && eventStatus !== undefined
  const eventEnded = now !== null && eventEndDatetime !== undefined && Date.parse(eventEndDatetime) <= now
  const eventUnavailable = eventStatus === 'cancelled' || eventStatus === 'completed' || eventEnded
  const canEditPeriod = now !== null && hasEventDetails && !eventUnavailable && data.registrationRequired
  const openingLocked = data.opensAt !== null && (data.registrationState !== 'not_yet_open'
    || (now !== null && Date.parse(data.opensAt) <= now))

  async function savePeriod(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaveError('')
    setSaveSuccess('')
    // The picker has minute precision; keep the stored instant (with seconds) when the field is untouched.
    const opening = savedRegistration.opensAt !== null && opensAtInput === toSingaporeInput(savedRegistration.opensAt)
      ? savedRegistration.opensAt
      : toSingaporeOffsetDateTime(opensAtInput)
    const closing = toSingaporeOffsetDateTime(closesAtInput)
    const submittedAt = Date.now()
    if (!opening || !closing) {
      setSaveError('Enter both a valid registration opening and closing date and time in Singapore time.')
      return
    }
    if (savedRegistration.opensAt !== null) {
      const openingHasPassed = Date.parse(savedRegistration.opensAt) <= submittedAt
      if (openingHasPassed && Date.parse(opening) !== Date.parse(savedRegistration.opensAt)) {
        setSaveError('Registration has already opened; only the closing date and time can be changed.')
        return
      }
      if (!openingHasPassed && Date.parse(opening) < submittedAt) {
        setSaveError('The registration opening date and time cannot be in the past.')
        return
      }
    } else if (Date.parse(opening) < submittedAt) {
      setSaveError('The registration opening date and time cannot be in the past.')
      return
    }
    if (Date.parse(closing) <= Date.parse(opening)) {
      setSaveError('The registration closing date and time must be after the opening date and time.')
      return
    }
    if (!eventStartDatetime || Date.parse(closing) > Date.parse(eventStartDatetime)) {
      setSaveError('Registration must close no later than the event start date and time.')
      return
    }

    setSaving(true)
    try {
      const updated = await apiClient.put<RegistrationInfoDto>(`/events/${eventId}/registration-period`, {
        opensAt: opening,
        closesAt: closing,
      })
      if (!updated || typeof updated.registrationState !== 'string') {
        throw new Error('The saved registration period could not be confirmed. Please reload and try again.')
      }
      setData(updated)
      setPeriodDraft(null)
      setSaveSuccess(`Registration period saved. Status: ${STATE_LABELS[updated.registrationState]}.`)
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : 'Could not save the registration period. Nothing was changed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="registrations" role="region" aria-labelledby="registrations-heading">
      <div className="registrations__head">
        <div>
          <h2 id="registrations-heading">Registration information</h2>
          <p className="field-hint">{data.eventName}</p>
        </div>
        <span className={`registrations__badge registrations__badge--${data.registrationState}`}>
          {STATE_LABELS[data.registrationState]}
        </span>
      </div>
      {!data.registrationRequired ? (
        <p>{data.message ?? 'Registration is not required for this event.'}</p>
      ) : (
        <>
          <div className="registrations__stats">
            <div><strong>{data.capacity ?? '∞'}</strong><span>Capacity</span></div>
            <div><strong>{data.confirmedCount}</strong><span>Confirmed</span></div>
            <div><strong>{data.placesRemaining ?? '∞'}</strong><span>Places remaining</span></div>
          </div>
          {data.capacity ? (
            <div className="registrations__bar" role="img" aria-label={`${pct}% of places taken`}>
              <div style={{ width: `${pct}%` }} />
            </div>
          ) : null}
          <p className="field-hint">
            Opens: {data.opensAt ? formatEventDateTime(data.opensAt) : 'Not set'} · Closes:{' '}
            {data.closesAt ? formatEventDateTime(data.closesAt) : 'Not set'} (Singapore time). Only confirmed
            registrations count towards capacity.
          </p>
          {canEditPeriod ? (
            <form className="registrations__period" onSubmit={(event) => { void savePeriod(event) }} noValidate>
              <h3>Registration period</h3>
              <p className="field-hint">Choose both date and time in Singapore time (SGT, UTC+08:00).</p>
              <div className="registrations__period-fields">
                <div className="field">
                  <label htmlFor={`registration-opens-${eventId}`}>Registration opens (SGT)</label>
                  <input id={`registration-opens-${eventId}`} type="datetime-local" step="60" required
                    value={opensAtInput} disabled={saving || openingLocked}
                    onChange={(event) => setPeriodDraft({
                      opensAt: event.target.value,
                      closesAt: periodDraft?.closesAt ?? closesAtInput,
                    })} />
                </div>
                <div className="field">
                  <label htmlFor={`registration-closes-${eventId}`}>Registration closes (SGT)</label>
                  <input id={`registration-closes-${eventId}`} type="datetime-local" step="60" required
                    value={closesAtInput} disabled={saving}
                    onChange={(event) => setPeriodDraft({
                      opensAt: periodDraft?.opensAt ?? opensAtInput,
                      closesAt: event.target.value,
                    })} />
                </div>
              </div>
              {openingLocked && <p className="field-hint">Registration has opened; only the closing date and time can be changed.</p>}
              {saveError && <p className="field-error" role="alert">{saveError}</p>}
              {saveSuccess && <p role="status">{saveSuccess}</p>}
              <Button type="submit" disabled={saving || eventUnavailable}>
                {saving ? 'Saving…' : 'Save registration period'}
              </Button>
            </form>
          ) : null}
          {eventUnavailable && data.registrationRequired && (
            <p className="field-hint">The registration period cannot be changed for a cancelled or ended event.</p>
          )}
          {data.attendees.length === 0 ? (
            <p>{data.message ?? 'No registrations yet.'}</p>
          ) : (
            <div className="registrations__scroll">
              <table className="registrations__table">
                <caption className="sr-only">Registered attendees</caption>
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Reference</th>
                    <th scope="col">Registered</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {data.attendees.map((a) => (
                    <tr key={a.registrationId}>
                      <td>{a.name}</td>
                      <td>{a.reference}</td>
                      <td>{formatEventDateTime(a.registeredAt)}</td>
                      <td>
                        <span className={`registrations__pill registrations__pill--${a.status}`}>
                          {STATUS_LABELS[a.status] ?? a.status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Card>
  )
}

function toSingaporeInput(value: string | null): string {
  if (!value) return ''
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(value))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`
}

function toSingaporeOffsetDateTime(value: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const [, yearText, monthText, dayText, hourText, minuteText] = match
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  const hour = Number(hourText)
  const minute = Number(minuteText)
  const instant = new Date(0)
  instant.setUTCFullYear(year, month - 1, day)
  instant.setUTCHours(hour - 8, minute, 0, 0)
  const singapore = new Date(instant.getTime() + 8 * 60 * 60 * 1000)
  if (singapore.getUTCFullYear() !== year || singapore.getUTCMonth() !== month - 1
      || singapore.getUTCDate() !== day || singapore.getUTCHours() !== hour
      || singapore.getUTCMinutes() !== minute) return null
  return `${value}:00+08:00`
}
