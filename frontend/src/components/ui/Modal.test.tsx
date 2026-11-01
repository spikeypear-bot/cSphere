// @stories EC04 EC03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Modal } from './Modal'

describe('EC04 shared modal', () => {
  afterEach(cleanup)

  it('EC04 (happy): opens as a dialog named by its title, with its content and footer', () => {
    render(<Modal title="Marina Bay Hall" onClose={() => {}} footer={<button type="button">Show on map</button>}>
      <p>400 people</p>
    </Modal>)

    const dialog = screen.getByRole('dialog', { name: 'Marina Bay Hall' })
    expect(within(dialog).getByText('400 people')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Show on map' })).toBeInTheDocument()
  })

  it('EC04 (happy): the Close button closes it', async () => {
    const onClose = vi.fn()
    render(<Modal title="Marina Bay Hall" onClose={onClose}><p>400 people</p></Modal>)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('EC04 (happy): Escape closes it', () => {
    const onClose = vi.fn()
    render(<Modal title="Marina Bay Hall" onClose={onClose}><p>400 people</p></Modal>)

    // The browser turns Escape into a cancel event, then closes the dialog.
    const dialog = screen.getByRole('dialog', { name: 'Marina Bay Hall' }) as HTMLDialogElement
    fireEvent(dialog, new Event('cancel', { cancelable: true }))
    dialog.close()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('EC04 (happy): a footer action closes it through the dialog, once', async () => {
    const onClose = vi.fn()
    render(<Modal title="Marina Bay Hall" onClose={onClose}
      footer={(close) => <button type="button" onClick={close}>Add to request</button>}>
      <p>400 people</p>
    </Modal>)

    await userEvent.click(screen.getByRole('button', { name: 'Add to request' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
