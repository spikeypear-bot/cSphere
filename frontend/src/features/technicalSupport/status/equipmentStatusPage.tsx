import { useEffect, useState } from 'react'
import {
  BLOCK_STATUSES,
  type BlockStatus,
  type EquipmentUnit,
  type StatusPeriod,
  type TimePeriod,
} from './equipmentStatus.types'
import {
  ApiError,
  addStatusPeriod,
  fetchEquipmentUnits,
  fetchStatusPeriods,
  removeStatusPeriod,
} from './equipmentStatusApi'
import { countAvailableByType, describePeriod, unitKey } from './equipmentStatusUtils'

export function EquipmentStatusPage() {
  // ---- The period we are VIEWING ----
  const [period, setPeriod] = useState<TimePeriod>({
    start: '2026-09-25T09:00',
    end: '2026-09-25T17:00',
  })
  const [units, setUnits] = useState<EquipmentUnit[]>([])
  const [loading, setLoading] = useState(false)
  // Bumping this number makes both lists reload from the backend.
  const [reloadCount, setReloadCount] = useState(0)

  // ---- The selected unit and its blocks ----
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [blocks, setBlocks] = useState<StatusPeriod[]>([])

  // ---- The "mark as faulty/unavailable" form (a draft until saved) ----
  const [draftStatus, setDraftStatus] = useState<BlockStatus>('Unavailable')
  const [draftStart, setDraftStart] = useState('')
  const [draftEnd, setDraftEnd] = useState('')
  const [indefinite, setIndefinite] = useState(false)

  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // ---- Values worked out from the state ----
  const periodIsValid =
    period.start !== '' && period.end !== '' && period.end > period.start
  const selectedUnit = units.find((u) => unitKey(u) === selectedKey) ?? null
  const selectedEquipmentId = selectedUnit?.equipmentId
  const selectedSerial = selectedUnit?.serialNumber
  const counts = countAvailableByType(units)
  const formIsValid =
    draftStart !== '' && (indefinite || (draftEnd !== '' && draftEnd > draftStart))

  // ---- Load every unit's status for the viewed period ----
  useEffect(() => {
    if (!periodIsValid) {
      setUnits([])
      return
    }
    let cancelled = false
    setLoading(true)
    fetchEquipmentUnits(period)
      .then((data) => { if (!cancelled) setUnits(data) })
      .catch(() => { if (!cancelled) setError('Could not load equipment.') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [period, periodIsValid, reloadCount])

  // ---- Load the selected unit's blocks ----
  useEffect(() => {
    if (!selectedEquipmentId || !selectedSerial) {
      setBlocks([])
      return
    }
    let cancelled = false
    fetchStatusPeriods(selectedEquipmentId, selectedSerial)
      .then((data) => { if (!cancelled) setBlocks(data) })
      .catch(() => { if (!cancelled) setError('Could not load this unit\'s periods.') })
    return () => { cancelled = true }
  }, [selectedEquipmentId, selectedSerial, reloadCount])

  function resetForm() {
    setDraftStatus('Unavailable')
    setDraftStart(period.start)
    setDraftEnd(period.end)
    setIndefinite(false)
  }

  function handleSelect(unit: EquipmentUnit) {
    setSelectedKey(unitKey(unit))
    resetForm()
    setMessage(null)
    setError(null)
  }

  // Cancel = throw the draft away. Nothing was saved, so nothing changes.
  function handleCancel() {
    resetForm()
    setError(null)
  }

  async function handleSave() {
    if (!selectedUnit || !formIsValid) return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      await addStatusPeriod(
        selectedUnit.equipmentId,
        selectedUnit.serialNumber,
        draftStatus,
        draftStart,
        indefinite ? null : draftEnd,
      )
      setMessage(
        `${selectedUnit.equipmentName} ${selectedUnit.serialNumber} marked ${draftStatus} ` +
          (indefinite ? 'until you change it back.' : 'for the chosen dates.'),
      )
      resetForm()
      setReloadCount((n) => n + 1)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError('This unit already has a status for part of those dates. Remove that period first.')
      } else {
        setError('Could not save the change. The previous status has been kept.')
      }
    } finally {
      setSaving(false)
    }
  }

  async function handleRemove(block: StatusPeriod) {
    if (!selectedUnit) return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      await removeStatusPeriod(block.id)
      setMessage(
        `${selectedUnit.equipmentName} ${selectedUnit.serialNumber} is available again for that period.`,
      )
      setReloadCount((n) => n + 1)
    } catch {
      setError('Could not remove that period. Nothing was changed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section>
      <h1>Equipment Status</h1>

      <fieldset>
        <legend>Date and time period to view</legend>
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
            Status for the period above: <strong>{selectedUnit.status}</strong>
          </p>

          <h3>Faulty / unavailable periods</h3>
          {blocks.length === 0 ? (
            <p>None. This unit is available at all times.</p>
          ) : (
            <ul>
              {blocks.map((block) => (
                <li key={block.id}>
                  <strong>{block.status}</strong>: {describePeriod(block)}{' '}
                  <button type="button" onClick={() => handleRemove(block)} disabled={saving}>
                    Remove (mark available)
                  </button>
                </li>
              ))}
            </ul>
          )}

          <h3>Mark as faulty or unavailable</h3>
          <label htmlFor="block-status">Status</label>
          <select
            id="block-status"
            value={draftStatus}
            onChange={(e) => setDraftStatus(e.target.value as BlockStatus)}
            disabled={saving}
          >
            {BLOCK_STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>

          <label htmlFor="block-start">From</label>
          <input
            id="block-start"
            type="datetime-local"
            value={draftStart}
            onChange={(e) => setDraftStart(e.target.value)}
            disabled={saving}
          />

          <label>
            <input
              type="checkbox"
              checked={indefinite}
              onChange={(e) => setIndefinite(e.target.checked)}
              disabled={saving}
            />
            No end date (until I change it back)
          </label>

          {!indefinite && (
            <>
              <label htmlFor="block-end">Until</label>
              <input
                id="block-end"
                type="datetime-local"
                value={draftEnd}
                onChange={(e) => setDraftEnd(e.target.value)}
                disabled={saving}
              />
            </>
          )}

          {!formIsValid && (
            <p role="alert">
              Choose a start time, and an end time after it (or tick "No end date").
            </p>
          )}

          <button type="button" onClick={handleSave} disabled={!formIsValid || saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button type="button" onClick={handleCancel} disabled={saving}>
            Cancel
          </button>
        </div>
      )}

      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}