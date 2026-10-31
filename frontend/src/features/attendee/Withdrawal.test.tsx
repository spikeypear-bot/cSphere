// @stories AT08 AT01 AT03 AT04
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { RegistrationDetailsPage } from './RegistrationDetailsPage'
import { NotificationBell } from '../notifications/NotificationBell'
import type { RegistrationDetails } from './registrationView'

const original: RegistrationDetails = {
  registrationId: 'r1', reference: 'CS-ORIGINAL', registeredAt: '2026-12-01T00:00:00Z', withdrawnAt: null,
  registrationStatus: 'confirmed', eventId: 'e1', eventName: 'Community conference',
  startDatetime: '2027-03-01T01:00:00Z', endDatetime: '2027-03-01T09:00:00Z',
  eventStatus: 'confirmed', eventDetailsAvailable: true, withdrawalAvailable: true,
  withdrawalReason: 'Withdrawal is available before the event starts.',
}
const withdrawn: RegistrationDetails = { ...original, registrationStatus: 'withdrawn',
  withdrawnAt: '2026-12-02T01:00:00Z', withdrawalAvailable: false,
  withdrawalReason: 'This registration has already been withdrawn.' }
const response = (data: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(data) }) as Response
let current: RegistrationDetails
let post: () => Promise<Response>
let read: () => Promise<Response>
let fetchMock: Mock<(url: string, init?: RequestInit) => Promise<Response>>

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh',
    userId: 'owner', username: 'att1', role: 'attendee', organisation: null }))
  current = { ...original }
  post = async () => { current = { ...withdrawn }; return response(current) }
  read = async () => response(current)
  fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url.endsWith('/withdraw')) return post()
    if (url === '/api/attendee/registrations/r1') return read()
    if (url === '/api/attendee/events/e1') return Promise.resolve(response({ venues: [] }))
    if (url.includes('unread-count')) return Promise.resolve(response({ count: current.registrationStatus === 'withdrawn' ? 1 : 0 }))
    if (url === '/api/notifications') return Promise.resolve(response(current.registrationStatus === 'withdrawn' ? [{
      notificationId: 'n1', type: 'attendee_registration_withdrawn', eventId: 'e1', eventName: original.eventName,
      message: 'Your registration has been withdrawn. Reference: CS-ORIGINAL', read: false,
      occurredAt: withdrawn.withdrawnAt, linkPath: '/attendee/my-registrations/r1',
    }] : []))
    if (url === '/api/notifications/n1/read' && init?.method === 'PATCH') return Promise.resolve(response({}))
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { cleanup(); window.localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function page(withBell = false) {
  return render(<MemoryRouter initialEntries={['/attendee/my-registrations/r1']}><SessionProvider>
    {withBell && <NotificationBell />}
    <Routes><Route path="/attendee/my-registrations/:registrationId" element={<RegistrationDetailsPage />} /></Routes>
  </SessionProvider></MemoryRouter>)
}
async function openDialog() {
  fireEvent.click(await screen.findByRole('button', { name: 'Withdraw Registration' }))
  return screen.getByRole('dialog', { name: 'Withdraw your registration?' })
}
const posts = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/withdraw'))

describe('AT08 withdrawal', () => {
  it('AT08 (negative): opening, cancelling or pressing Escape sends no withdrawal and preserves registration', async () => {
    page()
    let dialog = await openDialog()
    expect(within(dialog).getByText(original.eventName)).toBeInTheDocument()
    expect(within(dialog).getByText(original.reference)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep registration' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    dialog = await openDialog()
    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
    expect(posts()).toHaveLength(0)
  })

  it('AT08 AT03 AT04 (happy): confirmation updates history/date immediately and refreshes the real notification bell', async () => {
    page(true)
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm withdrawal' }))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    expect(screen.getByText('2 December 2026 at 9:00 am (SGT)')).toBeInTheDocument()
    expect(screen.getByText(original.reference)).toBeInTheDocument()
    expect(screen.getByText('1 December 2026 at 8:00 am (SGT)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
    expect(posts()).toHaveLength(1)
    await waitFor(() => expect(screen.getByRole('button', { name: /Notifications, 1 unread/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /Notifications/ }))
    expect(await screen.findByText('Your registration for "Community conference" has been withdrawn')).toBeInTheDocument()
  })

  it('AT08 (conflict): a pending submission cannot be sent twice or dismissed as if cancelled', async () => {
    let complete!: (value: Response) => void
    post = () => new Promise(resolve => { complete = resolve })
    page()
    const dialog = await openDialog()
    const confirm = within(dialog).getByRole('button', { name: 'Confirm withdrawal' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(posts()).toHaveLength(1)
    expect(within(dialog).getByRole('button', { name: 'Withdrawing…' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Keep registration' })).toBeDisabled()
    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }))
    expect(dialog).toBeInTheDocument()
    current = withdrawn
    await act(async () => complete(response(withdrawn)))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
  })

  it.each(['cancelled', 'completed', 'confirmed'] as const)('AT08 (negative): %s ineligible event explains why withdrawal is unavailable', async eventStatus => {
    current = { ...original, eventStatus, withdrawalAvailable: false, withdrawalReason: 'Withdrawal is unavailable because the event has started.' }
    page()
    expect(await screen.findByText(/Unavailable\. Withdrawal is unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
    expect(posts()).toHaveLength(0)
  })

  it('AT08 (happy): a registration during re-confirmation still offers withdrawal', async () => {
    current = { ...original, eventStatus: 'reconfirming' }
    page()
    expect(await screen.findByRole('button', { name: 'Withdraw Registration' })).toBeEnabled()
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
  })

  it('AT08 (conflict): eligibility changing while the dialog is open disables confirmation', async () => {
    page()
    const dialog = await openDialog()
    current = { ...original, eventStatus: 'cancelled', withdrawalAvailable: false,
      withdrawalReason: 'Withdrawal is unavailable because the event has been cancelled.' }
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Confirm withdrawal' })).toBeDisabled())
    expect(within(dialog).getByText(current.withdrawalReason)).toBeInTheDocument()
    expect(posts()).toHaveLength(0)
  })

  it('AT08 (conflict): a stale confirmation is reconciled with cancellation and does not report success', async () => {
    post = async () => {
      current = { ...original, eventStatus: 'cancelled', withdrawalAvailable: false,
        withdrawalReason: 'Withdrawal is unavailable because the event has been cancelled.' }
      return response({ message: current.withdrawalReason }, 409)
    }
    page()
    fireEvent.click(within(await openDialog()).getByRole('button', { name: 'Confirm withdrawal' }))
    expect(await screen.findByText('Cancelled', { selector: '[data-attendee-badge="registration"]' })).toBeInTheDocument()
    expect(screen.queryByText('Your registration has been withdrawn.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
    expect(posts()).toHaveLength(1)
  })

  it('AT08 (failure): a lost response after commit discovers withdrawal without sending a second request', async () => {
    post = async () => { current = withdrawn; throw new TypeError('Lost response') }
    page()
    fireEvent.click(within(await openDialog()).getByRole('button', { name: 'Confirm withdrawal' }))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
    expect(posts()).toHaveLength(1)
  })

  it('AT08 (failure): failed reconciliation disables retry until a fresh server read succeeds', async () => {
    page()
    const dialog = await openDialog()
    post = async () => { throw new TypeError('Offline') }
    read = async () => { throw new TypeError('Offline') }
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm withdrawal' }))
    const refresh = await screen.findByRole('button', { name: 'Refresh registration status' })
    await waitFor(() => expect(refresh).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Withdraw Registration' })).toBeDisabled()
    read = async () => response(original)
    fireEvent.click(refresh)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Withdraw Registration' })).toBeEnabled())
    expect(posts()).toHaveLength(1)
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
  })

  it('AT08 (conflict): an older background read cannot overwrite a successful withdrawal', async () => {
    page()
    await screen.findByText(original.reference)
    let stale!: (value: Response) => void
    read = () => new Promise(resolve => { stale = resolve })
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(stale).toBeTypeOf('function'))
    read = async () => response(current)
    fireEvent.click(within(await openDialog()).getByRole('button', { name: 'Confirm withdrawal' }))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    await act(async () => stale(response(original)))
    expect(screen.getByText('Withdrawn')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
  })

  it('AT08 (conflict): delayed reconciliation cannot undo a newer withdrawn read', async () => {
    page()
    const dialog = await openDialog()
    let delayed!: (value: Response) => void
    let reads = 0
    post = async () => { throw new TypeError('Lost withdrawal response') }
    read = () => ++reads === 1 ? new Promise(resolve => { delayed = resolve }) : Promise.resolve(response(withdrawn))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm withdrawal' }))
    await waitFor(() => expect(delayed).toBeTypeOf('function'))
    current = withdrawn
    fireEvent(window, new Event('focus'))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    await act(async () => delayed(response(original)))
    expect(screen.getByText('Withdrawn')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw Registration' })).not.toBeInTheDocument()
    expect(screen.getByText('Your registration has been withdrawn.')).toBeInTheDocument()
    expect(posts()).toHaveLength(1)
  })

  it('AT08 (failure): an older reconciliation error cannot replace a newer successful read', async () => {
    page()
    const dialog = await openDialog()
    let fail!: (reason: Error) => void
    let reads = 0
    post = async () => { throw new TypeError('Lost response') }
    read = () => ++reads === 1 ? new Promise((_, reject) => { fail = reject }) : Promise.resolve(response(withdrawn))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm withdrawal' }))
    await waitFor(() => expect(fail).toBeTypeOf('function'))
    current = withdrawn
    fireEvent(window, new Event('focus'))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    await act(async () => fail(new TypeError('Old read failed')))
    expect(screen.getByText('Withdrawn')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(posts()).toHaveLength(1)
  })

  it('AT08 (conflict): a withdrawal finishing after navigation cannot clear another registration view', async () => {
    const second = { ...original, registrationId: 'r2', reference: 'CS-SECOND', eventId: 'e2', eventName: 'Another event' }
    let secondReads = 0
    const normal = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/attendee/registrations/r2') {
        return ++secondReads === 1 ? Promise.resolve(response(second)) : new Promise<Response>(() => {})
      }
      if (url === '/api/attendee/events/e2') return Promise.resolve(response({ venues: [] }))
      return normal(url, init)
    })
    let complete!: (value: Response) => void
    post = () => new Promise(resolve => { complete = resolve })
    const router = createMemoryRouter([{ path: '/attendee/my-registrations/:registrationId', element: <RegistrationDetailsPage /> }],
      { initialEntries: ['/attendee/my-registrations/r1'] })
    render(<RouterProvider router={router} />)
    fireEvent.click(within(await openDialog()).getByRole('button', { name: 'Confirm withdrawal' }))
    await act(async () => { await router.navigate('/attendee/my-registrations/r2') })
    expect(await screen.findByText('CS-SECOND')).toBeInTheDocument()
    current = withdrawn
    await act(async () => complete(response(withdrawn)))
    expect(screen.getByText('CS-SECOND')).toBeInTheDocument()
    expect(screen.queryByText('CS-ORIGINAL')).not.toBeInTheDocument()
  })
})
