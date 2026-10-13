import { useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import type { ReadinessDto, ReadinessItem } from './readinessTypes'
import './safetyOfficer.css'

interface Props {
  readiness: ReadinessDto
  editable: boolean
  savingItem: string | null
  onSave: (item: ReadinessItem, checked: boolean, note: string) => Promise<void>
}

export function ReadinessChecklistPanel({ readiness, editable, savingItem, onSave }: Props) {
  return (
    <Card className="safety-panel">
      <h2>Operational readiness checklist</h2>
      <p role="status">{readiness.completedItems} of {readiness.totalItems} items complete</p>
      <ul className="readiness-list">
        {readiness.items.map((item) => (
          <ReadinessItemRow
            key={`${item.itemKey}:${item.note}`}
            item={item}
            editable={editable}
            saving={savingItem === item.itemKey}
            onSave={(checked, note) => onSave(item, checked, note)}
          />
        ))}
      </ul>
    </Card>
  )
}

function ReadinessItemRow({
  item, editable, saving, onSave,
}: {
  item: ReadinessItem
  editable: boolean
  saving: boolean
  onSave: (checked: boolean, note: string) => Promise<void>
}) {
  const [note, setNote] = useState(item.note)

  return (
    <li className="readiness-item">
      <label className="readiness-item__check">
        <input
          type="checkbox"
          checked={item.checked}
          disabled={!editable || saving}
          onChange={(event) => void onSave(event.target.checked, note)}
        />
        <span>{item.label}</span>
      </label>
      {editable ? (
        <div className="readiness-item__notes">
          <label htmlFor={`readiness-note-${item.itemKey}`}>Notes</label>
          <textarea
            id={`readiness-note-${item.itemKey}`}
            value={note}
            maxLength={2000}
            disabled={saving}
            onChange={(event) => setNote(event.target.value)}
          />
          <Button variant="secondary" disabled={saving || note === item.note} onClick={() => void onSave(item.checked, note)}>
            {saving ? 'Saving…' : 'Save note'}
          </Button>
        </div>
      ) : item.note ? <p className="readiness-item__note">{item.note}</p> : null}
      <p className="readiness-item__audit">
        {item.checkedBy
          ? `${item.checked ? 'Checked' : 'Last checked'} by ${item.checkedBy}${item.checkedAt ? ` · ${new Date(item.checkedAt).toLocaleString()}` : ''}`
          : 'Not checked yet'}
      </p>
      {item.updatedBy && item.updatedBy !== item.checkedBy ? (
        <p className="readiness-item__audit">
          Last updated by {item.updatedBy}{item.updatedAt ? ` · ${new Date(item.updatedAt).toLocaleString()}` : ''}
        </p>
      ) : null}
    </li>
  )
}
