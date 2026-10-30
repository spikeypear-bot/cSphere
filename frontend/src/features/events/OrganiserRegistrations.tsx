import { useEffect, useState, type FormEvent } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './OrganiserRegistrations.css'
import type { RegistrationInfoDto, RegistrationState, RegistrationStatus } from '../../types/event'

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

/** EO10: registration information for the organiser's own event; EO11: set the registration capacity. Reloads on every mount. */
export function OrganiserRegistrations({ eventId }: { eventId: string }) {
  const [data, setData] = useState<RegistrationInfoDto | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [capacityInput, setCapacityInput] = useState('')
  const [capacityError, setCapacityError] = useState<string | null>(null)
  const [capacitySaved, setCapacitySaved] = useState(false)
  const [saving, setSaving] = useState(false)

  async function saveCapacity(e: FormEvent) {
    e.preventDefault()
    setCapacitySaved(false)
    const text = capacityInput.trim()
    if (!/^\d+$/.test(text) || Number(text) < 1 || !Number.isSafeInteger(Number(text))) {
      setCapacityError('Capacity must be a positive whole number.')
      return
    }
    setSaving(true)
    setCapacityError(null)
    try {
      const updated = await apiClient.put<RegistrationInfoDto>(`/events/${eventId}/registration-capacity`, {
        capacity: Number(text),
      })
      setData(updated)
      setCapacityInput('')
      setCapacitySaved(true)
    } catch (err) {
      setCapacityError(err instanceof ApiClientError && err.message
        ? err.message
        : 'We could not save the capacity. The existing capacity is unchanged.')
    } finally {
      setSaving(false)
    }
  }

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

  const pct = data.capacity ? Math.min(100, Math.round((data.confirmedCount / data.capacity) * 100)) : 0
  const barState = pct >= 100 ? 'full' : pct >= 90 ? 'near' : 'ok'

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
            <div
              className={`registrations__bar registrations__bar--${barState}`}
              role="img"
              aria-label={`${pct}% of places taken`}
            >
              <div style={{ width: `${pct}%` }} />
            </div>
          ) : null}
          <form className="registrations__capacity" onSubmit={saveCapacity} noValidate>
            <label htmlFor="registration-capacity">Registration capacity</label>
            <div className="registrations__control">
              <input
                id="registration-capacity"
                inputMode="numeric"
                autoComplete="off"
                placeholder={data.capacity ? `Currently ${data.capacity}` : 'e.g. 50'}
                value={capacityInput}
                onChange={(ev) => { setCapacityInput(ev.target.value); setCapacitySaved(false) }}
                aria-invalid={capacityError ? true : undefined}
                aria-describedby="registration-capacity-hint registration-capacity-msg"
              />
              <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save capacity'}</Button>
            </div>
            <p id="registration-capacity-hint" className="registrations__hint">
              Whole number, at least {Math.max(1, data.confirmedCount)} (the current confirmed count).
            </p>
            <p
              id="registration-capacity-msg"
              className={`registrations__msg ${capacityError ? 'registrations__msg--error' : 'registrations__msg--success'}`}
              role={capacityError ? 'alert' : 'status'}
            >
              {capacityError ?? (capacitySaved ? `Capacity saved: ${data.capacity ?? ''} places.` : '')}
            </p>
          </form>
          <p className="field-hint">
            Opens: {data.opensAt ? formatEventDateTime(data.opensAt) : 'Not set'} · Closes when the event starts:{' '}
            {data.closesAt ? formatEventDateTime(data.closesAt) : 'Not set'} (Singapore time). Only confirmed
            registrations count towards capacity.
          </p>
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
