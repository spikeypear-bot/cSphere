// @stories SO-C3
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReadinessChecklistPanel } from './ReadinessChecklistPanel'
import type { ReadinessDto } from './readinessTypes'

const readiness: ReadinessDto = {
  eventId: 'event-1', approvalStatus: 'not_reviewed', approvalNote: null,
  reviewedBy: null, reviewedAt: null, completedItems: 0, totalItems: 1,
  items: [{ itemKey: 'venue', label: 'Venue ready', checked: false, note: '',
    checkedBy: null, checkedAt: null, updatedBy: null, updatedAt: null }],
}

afterEach(cleanup)

describe('SO-C3 optional readiness notes', () => {
  it('SO-C3 (boundary): keeps empty notes collapsed and checks an item without notes', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={onSave} />)
    expect(screen.getByRole('button', { name: 'Show notes for Venue ready' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Venue ready' }))
    expect(onSave).toHaveBeenCalledWith(readiness.items[0], true, '')
  })

  it('SO-C3 (happy): saves on blur and retains the disclosure and note across the saved response', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn().mockResolvedValue(undefined)
    const rendered = render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={onSave} />)
    await user.click(screen.getByRole('button', { name: 'Show notes for Venue ready' }))
    await user.type(screen.getByRole('textbox', { name: 'Notes (optional) for Venue ready' }), 'Side entrance checked')
    await user.click(screen.getByRole('button', { name: 'Hide notes for Venue ready' }))
    expect(onSave).toHaveBeenCalledWith(readiness.items[0], false, 'Side entrance checked')
    rendered.rerender(<ReadinessChecklistPanel readiness={{ ...readiness, items: [{ ...readiness.items[0], note: 'Side entrance checked' }] }}
      editable savingItem={null} onSave={onSave} />)
    expect(screen.getByRole('button', { name: 'Show notes for Venue ready' })).toHaveAttribute('aria-expanded', 'false')
    await user.click(screen.getByRole('button', { name: 'Show notes for Venue ready' }))
    expect(screen.getByRole('textbox')).toHaveValue('Side entrance checked')
    expect(screen.queryByRole('button', { name: 'Save note' })).not.toBeInTheDocument()
  })

  it('SO-C3 (negative): preserves existing read-only notes without editing controls', async () => {
    const saved = { ...readiness, items: [{ ...readiness.items[0], note: 'Keep exit clear' }] }
    render(<ReadinessChecklistPanel readiness={saved} editable={false} savingItem={null} onSave={vi.fn()} />)
    expect(screen.getByText('Keep exit clear')).toBeVisible()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Hide notes for Venue ready' }))
    expect(screen.getByText('Keep exit clear')).not.toBeVisible()
  })

  it('SO-C3 (boundary): shows saved editable notes and prevents changes while saving', () => {
    const saved = { ...readiness, items: [{ ...readiness.items[0], note: 'Keep exit clear' }] }
    render(<ReadinessChecklistPanel readiness={saved} editable savingItem="venue" onSave={vi.fn()} />)
    expect(screen.getByRole('textbox')).toHaveValue('Keep exit clear')
    expect(screen.getByRole('textbox')).toBeDisabled()
    expect(screen.getByRole('textbox').parentElement).toHaveAttribute('aria-busy', 'true')
  })

  it('SO-C3 (boundary): clicking the item checkbox saves a typed note and the new checked state together', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={onSave} />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Show notes for Venue ready' }))
    await user.type(screen.getByRole('textbox'), 'Ready')
    await user.click(screen.getByRole('checkbox', { name: 'Venue ready' }))
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave).toHaveBeenCalledWith(readiness.items[0], true, 'Ready')
  })

  it('SO-C3 (happy): a note saved elsewhere replaces the shown one when nothing was typed here', async () => {
    const rendered = render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={vi.fn()} />)
    rendered.rerender(<ReadinessChecklistPanel readiness={{ ...readiness, items: [{ ...readiness.items[0], note: 'From so2' }] }}
      editable savingItem={null} onSave={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: 'Show notes for Venue ready' }))
    expect(screen.getByRole('textbox')).toHaveValue('From so2')
  })

  it('SO-C3 (happy): a keyboard user who types a note, moves to the checkbox and on without ticking it still saves the note', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={onSave} />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Show notes for Venue ready' }))
    await user.type(screen.getByRole('textbox'), 'Exit clear')
    act(() => screen.getByRole('checkbox').focus())
    expect(onSave).not.toHaveBeenCalled()
    await user.tab()
    expect(onSave).toHaveBeenCalledWith(readiness.items[0], false, 'Exit clear')
  })

  it('SO-C3 (boundary): leaving the checkbox with nothing changed saves nothing', async () => {
    const onSave = vi.fn()
    render(<ReadinessChecklistPanel readiness={readiness} editable savingItem={null} onSave={onSave} />)
    act(() => screen.getByRole('checkbox').focus())
    await userEvent.tab()
    expect(onSave).not.toHaveBeenCalled()
  })

  it('SO-C3 (boundary): a read-only unchecked item with no note shows no notes control, and its audit trail has no times when none were recorded', () => {
    render(<ReadinessChecklistPanel editable={false} savingItem={null} onSave={vi.fn()} readiness={{ ...readiness, items: [
      { ...readiness.items[0], checkedBy: 'so1', checkedAt: null, updatedBy: 'so2', updatedAt: null },
    ] }} />)
    expect(screen.queryByRole('button', { name: /notes for/ })).not.toBeInTheDocument()
    expect(screen.getByText('Last checked by so1')).toBeInTheDocument()
    expect(screen.getByText('Last updated by so2')).toBeInTheDocument()
  })
})
