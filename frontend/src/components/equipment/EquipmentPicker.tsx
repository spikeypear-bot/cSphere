import { useId, useState } from 'react'
import { Button } from '../ui/Button'
import './equipment.css'

export interface EquipmentOption { equipmentId: string; equipmentName: string }
export interface EquipmentLine extends EquipmentOption { quantity: number }

/** One catalogue picker for EO requirements and EC requests. Selection stays
 * in the parent so the role's existing save and revision rules still apply. */
export function EquipmentPicker({ catalogue, selected, onToggle, onQuantityChange, selectedOnly = false, catalogueAtBottom = false, choicesOnly = false, disabled = false }: {
  catalogue: EquipmentOption[]
  selected: Record<string, string>
  onToggle: (id: string, checked: boolean) => void
  onQuantityChange: (id: string, quantity: string) => void
  choicesOnly?: boolean
  catalogueAtBottom?: boolean
  selectedOnly?: boolean
  disabled?: boolean
}) {
  const id = useId()
  const [editing, setEditing] = useState(!selectedOnly && !catalogueAtBottom)
  const [search, setSearch] = useState('')
  const options = [...catalogue, ...Object.keys(selected).filter(key => !catalogue.some(item => item.equipmentId === key))
    .map(key => ({ equipmentId: key, equipmentName: key }))]
    .sort((a, b) => a.equipmentName.localeCompare(b.equipmentName))
  const visible = options.filter(item => editing
    ? item.equipmentName.toLowerCase().includes(search.trim().toLowerCase()) || selected[item.equipmentId] !== undefined
    : selected[item.equipmentId] !== undefined)
  return <fieldset className="equipment-picker" disabled={disabled}>
    <legend>{choicesOnly ? 'All equipment' : 'Equipment requirements'}</legend>
    <div className="equipment-picker__toolbar">
      {editing ? <input type="search" aria-label="Search equipment" placeholder="Search equipment"
        value={search} onChange={event => setSearch(event.target.value)} />
        : <span className="field-hint">Requested equipment</span>}
      <span className="equipment-picker__count" role="status">{Object.keys(selected).length} selected</span>
      {selectedOnly && !catalogueAtBottom ? <Button type="button" variant="secondary" onClick={() => { setEditing(!editing); setSearch('') }}>
        {editing ? 'Done' : 'Edit equipment'}
      </Button> : null}
    </div>
    <div className="equipment-list">
      {visible.map(item => {
        const quantity = selected[item.equipmentId]
        const checked = quantity !== undefined
        return <div className="equipment-row" data-selected={checked} key={item.equipmentId}>
          {editing && !catalogueAtBottom ? <label className="equipment-row__name" htmlFor={`${id}-${item.equipmentId}`}>
            <input id={`${id}-${item.equipmentId}`} type="checkbox" checked={checked}
              onChange={event => onToggle(item.equipmentId, event.target.checked)} />
            {item.equipmentName}
          </label> : <span className="equipment-row__name">{item.equipmentName}</span>}
          {(checked || catalogueAtBottom) && !choicesOnly ? <div className="equipment-stepper">
            <Button type="button" variant="secondary" disabled={Number(quantity ?? 0) <= (catalogueAtBottom ? 0 : 1)}
              aria-label={`Decrease ${item.equipmentName} quantity`}
              onClick={() => catalogueAtBottom && Number(quantity) <= 1 ? onToggle(item.equipmentId, false)
                : onQuantityChange(item.equipmentId, String(Math.max(1, (Number(quantity) || 1) - 1)))}>−</Button>
            <input type="number" min={catalogueAtBottom ? 0 : 1} step="1" value={quantity ?? '0'} aria-label={`${item.equipmentName} quantity`}
              aria-invalid={checked && !/^[1-9]\d*$/.test(quantity)}
              onChange={event => catalogueAtBottom && event.target.value === '0' ? onToggle(item.equipmentId, false)
                : onQuantityChange(item.equipmentId, event.target.value)} />
            <Button type="button" variant="secondary" aria-label={`Increase ${item.equipmentName} quantity`}
              onClick={() => onQuantityChange(item.equipmentId, String((Number(quantity) || 0) + 1))}>+</Button>
          </div> : null}
        </div>
      })}
    </div>
    {catalogueAtBottom ? <Button type="button" variant="secondary" aria-expanded={editing}
      onClick={() => { setEditing(!editing); setSearch('') }}>
      Add or remove equipment
    </Button> : null}
    {visible.length === 0 ? <p className="field-hint">{search.trim() ? `No equipment matches “${search.trim()}”.`
      : 'No equipment selected.'}</p> : null}
  </fieldset>
}

/** TS and SO share the request layout without gaining permission to edit it. */
export function EquipmentSummary({ items, label = 'Requested equipment' }: { items: EquipmentLine[]; label?: string }) {
  return items.length ? <ul className="equipment-list equipment-selection-summary" aria-label={label}>
    {items.map(item => <li className="equipment-row" key={item.equipmentId}>
      <span className="equipment-row__name">{item.equipmentName}</span>
      <span className="equipment-row__quantity">× {item.quantity}</span>
    </li>)}
  </ul> : null
}
