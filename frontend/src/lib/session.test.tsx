import { afterEach, describe, expect, it } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UNAUTHORISED_EVENT } from './apiClient'
import { SessionProvider } from './session'
import { useSession } from './sessionContext'

function Probe() {
  const { role, loginAs } = useSession()
  return (
    <div>
      <span>role: {role ?? 'none'}</span>
      <button type="button" onClick={() => loginAs('organiser', 'Acme')}>
        log in
      </button>
    </div>
  )
}

describe('SessionProvider', () => {
  afterEach(() => {
    cleanup()
    window.localStorage.clear()
  })

  it('logs the user out when apiClient reports an unauthorised/expired-session response', async () => {
    const user = userEvent.setup()
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'log in' }))
    expect(screen.getByText('role: organiser')).toBeInTheDocument()

    act(() => {
      window.dispatchEvent(new CustomEvent(UNAUTHORISED_EVENT))
    })

    expect(screen.getByText('role: none')).toBeInTheDocument()
  })
})
