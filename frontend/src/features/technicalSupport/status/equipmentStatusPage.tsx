import { useEffect, useState } from 'react'
import {
  EQUIPMENT_STATUSES,
  type EquipmentItem,
  type EquipmentStatus,
  type TimePeriod,
} from './equipmentStatus.types'
import { fetchEquipment, saveEquipmentStatus } from './equipmentStatusApi'
import { countAvailableByType } from './equipmentStatusUtils'

export function EquipmentStatusPage() {
  // ---- State: things that can change and re-draw the screen ----
  const [period, setPeriod] = useState<TimePeriod>({
    start: '2026-09-25T09:00',
    end: '2026-09-25T17:00',
  })
  const [items, setItems] = useState<EquipmentItem[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draftStatus, setDraftStatus] = useState<EquipmentStatus>('Available') // unsaved choice
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // ---- Values worked out from the state above ----
  const periodIsValid =
    period.start !== '' && period.end !== '' && period.end > period.start
  const selectedItem = items.find((i) => i.id === selectedId) ?? null
  const hasUnsavedChange = selectedItem !== null && draftStatus !== selectedItem.status
  const counts = countAvailableByType(items)

  // ---- AC 1: load statuses whenever the period changes ----
  useEffect(() => {
    if (!periodIsValid) {
      setItems([])
      return
    }
    let cancelled = false // ignore late responses if the period changed again
    setLoading(true)
    setError(null)
    fetchEquipment(period)
      .then((data) => { if (!cancelled) setItems(data) })
      .catch(() => { if (!cancelled) setError('Could not load equipment.') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [period, periodIsValid])

  // ---- Choosing an item: the draft starts as its SAVED status ----
  function handleSelect(item: EquipmentItem) {
    setSelectedId(item.id)
    setDraftStatus(item.status)
    setMessage(null)
    setError(null)
  }

  // ---- AC 2 + 3: save, then show the updated status ----
  async function handleSave() {
    if (!selectedItem) return
    setSaving(true)
    setMessage(null)
    setError(null)
    try {
      const updated = await saveEquipmentStatus(selectedItem.id, draftStatus)
      setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
      setMessage(`${updated.name} is now ${updated.status}.`)
    } catch {
      // AC 4 (failure case): put the draft back to the saved status
      setDraftStatus(selectedItem.status)
      setError('Could not save the change. The previous status has been kept.')
    } finally {
      setSaving(false)
    }
  }

  // ---- AC 4 (cancel case): throw away the draft ----
  function handleCancel() {
    if (selectedItem) setDraftStatus(selectedItem.status)
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
        {items.map((item) => (
          <li key={item.id}>
            <button
              type="button"
              onClick={() => handleSelect(item)}
              aria-pressed={item.id === selectedId}
            >
              {item.name} — {item.status}
            </button>
          </li>
        ))}
      </ul>

      {selectedItem && (
        <div>
          <h2>{selectedItem.name}</h2>
          <p>
            Current status: <strong>{selectedItem.status}</strong>
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