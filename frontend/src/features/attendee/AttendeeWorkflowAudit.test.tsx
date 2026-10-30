// @stories AT01 AT02 AT03 AT04 AT08 AU04
import { afterEach, describe, expect, it, vi, type Mock } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom'
import App from '../../App'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'
import { AttendeeEventsPage } from './AttendeeEventsPage'
import { MyRegistrationsPage } from './MyRegistrationsPage'

const event = {
  eventId: 'e1', eventName: 'First event', description: 'First description', status: 'confirmed',
  startDatetime: '2027-03-01T01:00:00Z', endDatetime: '2027-03-01T09:00:00Z', updatedAt: null,
  accessibilityInformation: [], venues: [], registrationRequired: true, registrationCapacity: 2,
  availablePlaces: 2, registrationOpensAt: '2020-01-01T00:00:00Z', registrationClosesAt: '2027-03-01T01:00:00Z',
  registrationReference: null, registeredAt: null, registrationId: null, registrationStatus: null, attendeeInstructions: null,
}
const second = { ...event, eventId: 'e2', eventName: 'Second event', description: 'Second description' }
const saved = { registrationId: 'r1', reference: 'CS-FIRST', registeredAt: '2026-12-01T00:00:00Z', status: 'confirmed' }
const registered = { ...event, registrationId: 'r1', registrationReference: saved.reference,
  registeredAt: saved.registeredAt, registrationStatus: 'confirmed', availablePlaces: 1 }
const history = { ...saved, eventId: 'e1', eventName: event.eventName, registrationStatus: 'confirmed',
  eventStatus: 'confirmed', startDatetime: event.startDatetime, endDatetime: event.endDatetime,
  eventDetailsAvailable: true, withdrawalAvailable: true, withdrawalReason: 'Before event start.' }
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response

function mockApi(overrides: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  const fetchMock: Mock<(url: string, init?: RequestInit) => Promise<Response>> = vi.fn((url, init) => {
    const override = overrides(url, init)
    if (override) return override
    if (url === '/api/attendee/events/e1') return Promise.resolve(response(event))
    if (url === '/api/attendee/events/e2') return Promise.resolve(response(second))
    if (url === '/api/attendee/events') return Promise.resolve(response([event, second]))
    if (url === '/api/attendee/registrations/r1') return Promise.resolve(response(history))
    if (url === '/api/attendee/registrations') return Promise.resolve(response([history]))
    if (url === '/api/notifications') return Promise.resolve(response([]))
    if (url.endsWith('/unread-count')) return Promise.resolve(response({ count: 0 }))
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function page(path = '/attendee/events/e1') {
  const router = createMemoryRouter([
    { path: '/attendee/events/:eventId', element: <AttendeeEventDetailsPage /> },
    { path: '/attendee/browse', element: <AttendeeEventsPage /> },
    { path: '/attendee/my-registrations', element: <MyRegistrationsPage /> },
  ], { initialEntries: [path] })
  render(<RouterProvider router={router} />)
  return router
}
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.localStorage.clear() })

describe('AT01 AT02 AT03 AT04 AT08 attendee workflow audit', () => {
  it.each([404, 503])('AT02 (failure): a successful focus refresh clears an earlier HTTP %s error', async status => {
    let failed = true
    mockApi(url => url === '/api/attendee/events/e1' ? Promise.resolve(response(failed ? { message: 'Unavailable' } : event, failed ? status : 200)) : undefined)
    page()
    await screen.findByText(status === 404 ? /event is unavailable/ : /Event details could not be loaded/)
    failed = false
    fireEvent(window, new Event('focus'))
    expect(await screen.findByRole('heading', { name: 'First event' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Register for this event' })).toBeEnabled()
  })

  it('AT02 (conflict): navigating to another event removes the old details while the new read is pending', async () => {
    let complete!: (value: Response) => void
    mockApi(url => url === '/api/attendee/events/e2' ? new Promise(resolve => { complete = resolve }) : undefined)
    const router = page()
    await screen.findByRole('heading', { name: 'First event' })
    await act(async () => { await router.navigate('/attendee/events/e2') })
    expect(screen.queryByRole('heading', { name: 'First event' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Loading event details')
    await act(async () => complete(response(second)))
    expect(await screen.findByRole('heading', { name: 'Second event' })).toBeInTheDocument()
  })

  it('AT01 AT02 AT03 (conflict): registration completing after navigation cannot install its reference on a different event', async () => {
    let complete!: (value: Response) => void
    const fetchMock = mockApi((url, init) => init?.method === 'POST' && url.endsWith('/registrations') ? new Promise(resolve => { complete = resolve }) : undefined)
    const router = page()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    await act(async () => { await router.navigate('/attendee/events/e2') })
    expect(await screen.findByRole('heading', { name: 'Second event' })).toBeInTheDocument()
    await act(async () => complete(response(saved)))
    expect(screen.getByRole('heading', { name: 'Second event' })).toBeInTheDocument()
    expect(screen.queryByText('CS-FIRST')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Register for this event' })).toBeEnabled()
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/attendee/registrations/r1')).toHaveLength(0)
  })

  it('AT01 AT03 (conflict): a pre-registration read cannot undo the saved confirmation', async () => {
    let oldRead!: (value: Response) => void
    let savedOnServer = false
    let delayOldRead = false
    mockApi((url, init) => {
      if (init?.method === 'POST') { savedOnServer = true; return Promise.resolve(response(saved)) }
      if (url === '/api/attendee/events/e1') {
        if (savedOnServer) return new Promise<Response>(() => {})
        if (delayOldRead) return new Promise(resolve => { oldRead = resolve })
      }
      return undefined
    })
    page()
    await screen.findByRole('heading', { name: 'First event' })
    delayOldRead = true
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(oldRead).toBeTypeOf('function'))
    fireEvent.click(screen.getByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('CS-FIRST')).toBeInTheDocument()
    await act(async () => oldRead(response(event)))
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
    expect(screen.getByText('CS-FIRST')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })

  it('AT01 AT03 (failure): capacity refresh failure preserves a successfully saved confirmation', async () => {
    let committed = false
    mockApi((url, init) => {
      if (init?.method === 'POST') { committed = true; return Promise.resolve(response(saved)) }
      if (url === '/api/attendee/events/e1' && committed) return Promise.reject(new TypeError('Read offline'))
      return undefined
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('CS-FIRST')).toBeInTheDocument()
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
    expect(screen.queryByText(/Event details could not be loaded/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })

  it('AT01 AT03 (failure): a lost registration response discovers the saved record without a second POST', async () => {
    let committed = false
    const fetchMock = mockApi((url, init) => {
      if (init?.method === 'POST') { committed = true; return Promise.reject(new TypeError('Lost response')) }
      if (url === '/api/attendee/events/e1' && committed) return Promise.resolve(response(registered))
      return undefined
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('CS-FIRST')).toBeInTheDocument()
    expect(screen.getByText('Registration Confirmed')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('AT01 AT02 (conflict): a rejected registration refreshes full-event availability before offering another attempt', async () => {
    let full = false
    mockApi((url, init) => {
      if (init?.method === 'POST') { full = true; return Promise.resolve(response({ message: 'This event is full.' }, 409)) }
      if (url === '/api/attendee/events/e1' && full) return Promise.resolve(response({ ...event, availablePlaces: 0 }))
      return undefined
    })
    page()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('Full')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('This event is full.')
    expect(screen.queryByRole('button', { name: 'Register for this event' })).not.toBeInTheDocument()
  })

  it.each(['/attendee/browse', '/attendee/my-registrations'])('AT02 AT04 (boundary): reads slower than the polling interval can still display %s', async path => {
    vi.useFakeTimers()
    let complete!: (value: Response) => void
    let reads = 0
    mockApi(url => url === '/api/attendee/events' || url === '/api/attendee/registrations'
      ? ++reads === 1 ? new Promise(resolve => { complete = resolve }) : new Promise<Response>(() => {}) : undefined)
    page(path)
    await act(async () => {})
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    await act(async () => complete(response(path.endsWith('browse') ? [event] : [history])))
    expect(screen.getByText(path.endsWith('browse') ? 'First event' : 'CS-FIRST')).toBeInTheDocument()
    expect(screen.queryByText(/Loading events|Loading registrations/)).not.toBeInTheDocument()
  })

  it('AT01 AT02 AU04 (happy): the legacy registration URL opens the implemented event catalogue', async () => {
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh',
      userId: 'att1', username: 'att1', role: 'attendee', organisation: 'Acme' }))
    mockApi(() => undefined)
    render(<MemoryRouter initialEntries={['/attendee/register']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: 'Browse events' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'First event' })).toHaveAttribute('href', '/attendee/events/e1')
    expect(screen.queryByText('Skeleton')).not.toBeInTheDocument()
  })
})
