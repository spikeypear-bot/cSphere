// @stories EO01 EC07 TS01 SO-C3
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EquipmentPicker, EquipmentSummary } from './EquipmentPicker'
import { equipmentDraftFrom, equipmentRequirementsFrom } from '../../lib/equipmentRequirements'

const catalogue = [{ equipmentId: 'mixer', equipmentName: 'Audio mixer' }, { equipmentId: 'mic', equipmentName: 'Microphone' }]
function Picker() {
  const [selected, setSelected] = useState<Record<string, string>>({ mixer: '1' })
  return <EquipmentPicker selectedOnly catalogue={catalogue} selected={selected}
    onToggle={(id, checked) => setSelected(current => { const next = { ...current }; if (checked) next[id] = '1'; else delete next[id]; return next })}
    onQuantityChange={(id, quantity) => setSelected(current => ({ ...current, [id]: quantity }))} />
}
afterEach(cleanup)

describe('EO01 EC07 TS01 SO-C3 shared equipment presentation', () => {
  it('EC07 (happy): starts with requested items, edits the catalogue and keeps quantities on return', async () => {
    render(<Picker />)
    expect(screen.getByText('Audio mixer')).toBeInTheDocument()
    expect(screen.queryByText('Microphone')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Decrease Audio mixer quantity' })).toBeDisabled()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Increase Audio mixer quantity' }))
    expect(screen.getByLabelText('Audio mixer quantity')).toHaveValue(2)
    await user.click(screen.getByRole('button', { name: 'Edit equipment' }))
    await user.click(screen.getByLabelText('Microphone'))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.getByLabelText('Audio mixer quantity')).toHaveValue(2)
    expect(screen.getByLabelText('Microphone quantity')).toHaveValue(1)
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('EO01 (boundary): keeps an invalid editable quantity visible for validation and preserves unknown selected IDs', async () => {
    const changed = vi.fn()
    render(<EquipmentPicker catalogue={[]} selected={{ old: '0' }} onToggle={vi.fn()} onQuantityChange={changed} />)
    expect(screen.getByLabelText('old quantity')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('old')).toBeChecked()
  })

  it('TS01 SO-C3 (negative): read-only equipment has quantities and no mutation controls', () => {
    render(<EquipmentSummary items={[{ ...catalogue[0], quantity: 3 }]} />)
    expect(screen.getByRole('list', { name: 'Requested equipment' })).toHaveTextContent(/Audio mixer.*× 3/)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
  })

  it('EO01 (cross-cutting): legacy text and separate notes round-trip without losing selected quantities', () => {
    const text = equipmentRequirementsFrom({ mixer: '3' }, 'Bring cables', catalogue)
    expect(equipmentDraftFrom(text, catalogue)).toEqual({ selected: { mixer: '3' }, notes: 'Bring cables' })
    expect(equipmentDraftFrom('External mixer needed', catalogue)).toEqual({ selected: {}, notes: 'External mixer needed' })
    expect(equipmentDraftFrom(equipmentRequirementsFrom({}, 'Bring cables', catalogue), catalogue))
      .toEqual({ selected: {}, notes: 'Bring cables' })
    const unknown = 'Equipment items:\n2 × Retired equipment'
    // Generated catalogue lines stay separate from additional notes.
    expect(equipmentDraftFrom(unknown, catalogue)).toEqual({ selected: {}, notes: '' })
    expect(equipmentDraftFrom(unknown + '\n\nAdditional notes:\nBring cables', catalogue))
      .toEqual({ selected: {}, notes: 'Bring cables' })
  })
})
