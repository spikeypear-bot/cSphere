import { useRef, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { formatSingaporeDateTime } from '../../lib/singaporeTime'
import type { ReadinessDto, ReadinessItem } from './readinessTypes'
import './safetyOfficer.css'
import './ReadinessChecklistPanel.css'

interface Props {
  readiness: ReadinessDto
  editable: boolean
  savingItem: string | null
  onSave: (item: ReadinessItem, checked: boolean, note: string) => Promise<void>
}

export function ReadinessChecklistPanel({ readiness, editable, savingItem, onSave }: Props) {
  const flagged = new Set(readiness.flaggedItems ?? [])
  return (
    <Card className="safety-panel">
      <h2>Operational readiness checklist</h2>
      <p role="status">{readiness.completedItems} of {readiness.totalItems} items complete</p>
      {editable ? <p className="field-hint">Notes save when you leave the field.</p> : null}
      <ul className="readiness-list" aria-label="Readiness checklist">
        {readiness.items.map((item) => (
          <ReadinessItemRow
            key={item.itemKey}
            item={item}
            flagged={flagged.has(item.itemKey)}
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
  item, flagged, editable, saving, onSave,
}: {
  item: ReadinessItem
  flagged: boolean
  editable: boolean
  saving: boolean
  onSave: (checked: boolean, note: string) => Promise<void>
}) {
  const [note, setNote] = useState(item.note)
  const [savedNote, setSavedNote] = useState(item.note)
  const [notesExpanded, setNotesExpanded] = useState(Boolean(item.note.trim()))
  const checkbox = useRef<HTMLInputElement>(null)
  // Accept refreshed saved notes without discarding a local edit or resetting
  // the disclosure when an autosave response arrives.
  if (item.note !== savedNote) {
    setSavedNote(item.note)
    if (note === savedNote) setNote(item.note)
  }

  return (
    <li className={flagged ? 'readiness-item readiness-item--flagged' : 'readiness-item'}>
      <label className="readiness-item__check">
        <input
          ref={checkbox}
          type="checkbox"
          checked={item.checked}
          disabled={!editable || saving}
          onChange={(event) => void onSave(event.target.checked, note)}
          onBlur={() => { if (!saving && note !== item.note) void onSave(item.checked, note) }}
        />
        <span>{item.label}</span>
      </label>
      {flagged ? <p className="readiness-item__flag">Raised by the Safety Officer's latest decision</p> : null}
      {editable || item.note ? <Button type="button" variant="ghost" className="readiness-item__notes-toggle"
        aria-expanded={notesExpanded} aria-controls={`readiness-note-panel-${item.itemKey}`}
        aria-label={`${notesExpanded ? 'Hide' : 'Show'} notes for ${item.label}`}
        onClick={() => setNotesExpanded(expanded => !expanded)}>
        <span aria-hidden="true">{notesExpanded ? '−' : '+'}</span> Notes{editable ? ' (optional)' : ''}
      </Button> : null}
      {editable ? (
        <div id={`readiness-note-panel-${item.itemKey}`} className="readiness-item__notes" hidden={!notesExpanded} aria-busy={saving}>
          <textarea
            id={`readiness-note-${item.itemKey}`}
            className="safety-note-field"
            aria-label={`Notes (optional) for ${item.label}`}
            value={note}
            maxLength={2000}
            disabled={saving}
            onChange={(event) => setNote(event.target.value)}
            onBlur={event => {
              // Checking this same item saves its note and checkbox together.
              if (event.relatedTarget !== checkbox.current && !saving && note !== item.note) void onSave(item.checked, note)
            }}
          />
        </div>
      ) : item.note ? <p id={`readiness-note-panel-${item.itemKey}`} hidden={!notesExpanded} className="readiness-item__note">{item.note}</p> : null}
      <p className="readiness-item__audit">
        {item.checkedBy
          ? `${item.checked ? 'Checked' : 'Last checked'} by ${item.checkedBy}${item.checkedAt ? ` · ${formatSingaporeDateTime(item.checkedAt)}` : ''}`
          : 'Not checked yet'}
      </p>
      {item.updatedBy && item.updatedBy !== item.checkedBy ? (
        <p className="readiness-item__audit">
          Last updated by {item.updatedBy}{item.updatedAt ? ` · ${formatSingaporeDateTime(item.updatedAt)}` : ''}
        </p>
      ) : null}
    </li>
  )
}
