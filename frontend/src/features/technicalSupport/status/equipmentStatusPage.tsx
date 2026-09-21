import { useEffect, useState } from 'react'
import {
  EQUIPMENT_STATUSES,
  type EquipmentStatus,
  type EquipmentUnit,
  type TimePeriod,
} from './equipmentStatus.types'
import { fetchEquipmentUnits, saveUnitStatus } from './equipmentStatusApi'
import { countAvailableByType, unitKey } from './equipmentStatusUtils'

export function EquipmentStatusPage() {
  // ---- State ----
  const [period, setPeriod] = useState<TimePeriod>({
    start: '2026-09-25T09:00',
    end: '2026-09-25T17:00',
  })
  const [units, setUnits] = useState<EquipmentUnit[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [draftStatus, setDraftStatus] = useState<EquipmentStatus>('Available') // unsaved choice
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // ---- Values worked out from the state ----
  const periodIsValid =
    period.start !== '' && period.end !== '' && period.end > period.start
  const selectedUnit = units.find((u) => unitKey(u) === selectedKey) ?? null
  const hasUnsavedChange = selectedUnit !== null && draftStatus !== selectedUnit.status
  const counts = countAvailableByType(units)

  // ---- AC 1: load statuses when the period changes ----
  useEffect(() => {
    if (!periodIsValid) {
      setUnits([])
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchEquipmentUnits(period)
      .then((data) => { if (!cancelled) setUnits(data) })
      .catch(() => { if (!cancelled) setError('Could not load equipment.') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [period, periodIsValid])

  // ---- Choosing a unit: the draft starts as its SAVED status ----
  function handleSelect(unit: EquipmentUnit) {
    setSelectedKey(unitKey(unit))
    setDraftStatus(unit.status)
    setMessage(null)
    setError(null)
  }

  // ---- AC 2 + 3: save, then show the updated status ----
  async function handleSave() {
    if (!selectedUnit) return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      const updated = await saveUnitStatus(selectedUnit, draftStatus)
      setUnits((prev) => prev.map((u) => (unitKey(u) === unitKey(updated) ? updated : u)))
      setMessage(`${updated.equipmentName} ${updated.serialNumber} is now ${updated.status}.`)
    } catch {
      // AC 4 (failure): put the draft back to the saved status
      setDraftStatus(selectedUnit.status)
      setError('Could not save the change. The previous status has been kept.')
    } finally {
      setSaving(false)
    }
  }

  // ---- AC 4 (cancel): throw away the draft ----
  function handleCancel() {
    if (selectedUnit) setDraftStatus(selectedUnit.status)
  }

  return (
    <section>
      <h1>Equipment Status</h1>

      <fieldset>
        <legend>Date and time period</legend>
        <label htmlFor="period-start">Start</label>
        <input
          id="period-start"
          type="datetime-local"
          value={period.start}
          onChange={(e) => setPeriod({ ...period, start: e.target.value })}
        />
        <label htmlFor="period-end">End</label>
        <input
          id="period-end"
          type="datetime-local"
          value={period.end}
          onChange={(e) => setPeriod({ ...period, end: e.target.value })}
        />
      </fieldset>
      {!periodIsValid && <p role="alert">Please choose an end time after the start time.</p>}
      {loading && <p>Loading equipment…</p>}

      <h2>Availability</h2>
      <ul>
        {counts.map((c) => (
          <li key={c.typeName}>
            {c.typeName}: {c.availableCount} of {c.totalCount} available
          </li>
        ))}
      </ul>

      <h2>Equipment</h2>
      <ul>
        {units.map((unit) => (
          <li key={unitKey(unit)}>
            <button
              type="button"
              onClick={() => handleSelect(unit)}
              aria-pressed={unitKey(unit) === selectedKey}
            >
              {unit.equipmentName} {unit.serialNumber} — {unit.status}
            </button>
          </li>
        ))}
      </ul>

      {selectedUnit && (
        <div>
          <h2>
            {selectedUnit.equipmentName} {selectedUnit.serialNumber}
          </h2>
          <p>
            Current status: <strong>{selectedUnit.status}</strong>
          </p>

          <label htmlFor="status-select">Change status to</label>
          <select
            id="status-select"
            value={draftStatus}
            onChange={(e) => setDraftStatus(e.target.value as EquipmentStatus)}
            disabled={saving}
          >
            {EQUIPMENT_STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>

          <button type="button" onClick={handleSave} disabled={!hasUnsavedChange || saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button type="button" onClick={handleCancel} disabled={!hasUnsavedChange || saving}>
            Cancel
          </button>
        </div>
      )}

      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}