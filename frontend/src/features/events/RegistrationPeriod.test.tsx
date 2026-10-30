// @stories EO12
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { apiClient } from '../../lib/apiClient'
import { OrganiserRegistrations } from './OrganiserRegistrations'

vi.mock('../../lib/apiClient', () => ({
  apiClient: { get: vi.fn(), put: vi.fn() },
}))

const base = {
  eventId: 'event-1',
  eventName: 'Community Fair',
  registrationRequired: true,
  registrationState: 'not_yet_open',
  capacity: 10,
  confirmedCount: 2,
  placesRemaining: 8,
  opensAt: '2027-01-01T10:00:00+08:00',
  closesAt: '2027-01-02T09:00:00+08:00',
  message: null,
  attendees: [],
}

const eventStart = '2027-01-02T10:00:00+08:00'
const eventEnd = '2027-01-02T18:00:00+08:00'

function renderEditor(data = base, eventStatus: 'confirmed' | 'cancelled' | 'completed' = 'confirmed') {
  vi.mocked(apiClient.get).mockResolvedValue(data)
  return render(<OrganiserRegistrations eventId="event-1" eventStartDatetime={eventStart}
    eventEndDatetime={eventEnd} eventStatus={eventStatus} />)
}

afterEach(() => {
  cleanup()
  vi.mocked(apiClient.get).mockReset()
  vi.mocked(apiClient.put).mockReset()
})

describe('EO12 organiser registration period', () => {
  it('EO12 AC2, AC14 (happy): edits date/time in SGT and shows the returned period status after saving', async () => {
    const updated = {
      ...base,
      opensAt: '2027-01-01T10:15:00+08:00',
      closesAt: '2027-01-02T09:30:00+08:00',
      registrationState: 'open',
    }
    vi.mocked(apiClient.put).mockResolvedValue(updated)
    renderEditor()

    const opening = await screen.findByLabelText('Registration opens (SGT)')
    const closing = screen.getByLabelText('Registration closes (SGT)')
    await waitFor(() => {
      expect(opening).toHaveValue('2027-01-01T10:00')
      expect(closing).toHaveValue('2027-01-02T09:00')
    })
    fireEvent.change(opening, { target: { value: '2027-01-01T10:15' } })
    fireEvent.change(closing, { target: { value: '2027-01-02T09:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save registration period' }))

    expect(await screen.findByText('Registration period saved. Status: Open.')).toBeInTheDocument()
    expect(apiClient.put).toHaveBeenCalledWith('/events/event-1/registration-period', {
      opensAt: '2027-01-01T10:15:00+08:00',
      closesAt: '2027-01-02T09:30:00+08:00',
    })
  })

  it('EO12 AC3, AC15 (negative): refuses an equal opening and closing time without sending a save', async () => {
    renderEditor()
    const opening = await screen.findByLabelText('Registration opens (SGT)')
    const closing = screen.getByLabelText('Registration closes (SGT)')
    fireEvent.change(closing, { target: { value: '2027-01-01T10:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save registration period' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The registration closing date and time must be after the opening date and time.',
    )
    expect(apiClient.put).not.toHaveBeenCalled()
    expect(opening).toHaveValue('2027-01-01T10:00')
  })

  it('EO12 AC6 (boundary): disables opening edits after opening and leaves closing editable', async () => {
    renderEditor({
      ...base,
      opensAt: new Date(Date.now() - 60_000).toISOString(),
      closesAt: new Date(Date.now() + 60 * 60_000).toISOString(),
      registrationState: 'open',
    })

    expect(await screen.findByLabelText('Registration opens (SGT)')).toBeDisabled()
    expect(screen.getByLabelText('Registration closes (SGT)')).toBeEnabled()
    expect(screen.getByText('Registration has opened; only the closing date and time can be changed.')).toBeInTheDocument()
  })

  it('EO12 AC15 (failure): keeps the saved period visible when the API rejects the update', async () => {
    vi.mocked(apiClient.put).mockRejectedValue(new Error('Period could not be saved.'))
    renderEditor()
    const closing = await screen.findByLabelText('Registration closes (SGT)')
    fireEvent.change(closing, { target: { value: '2027-01-02T09:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save registration period' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Period could not be saved.')
    expect(screen.getByText(/Closes:/)).toHaveTextContent('2 Jan 2027, 09:00 am')
  })

  it('EO12 AC12 (negative): hides configuration when registration is not required', async () => {
    const noRegistration = { ...base, registrationRequired: false, registrationState: 'not_required' }
    renderEditor(noRegistration)
    expect(await screen.findByText('Registration is not required for this event.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Registration opens (SGT)')).not.toBeInTheDocument()
  })

  it('EO12 AC13 (negative): hides configuration for cancelled or ended events', async () => {
    const { unmount } = renderEditor(base, 'cancelled')
    expect(await screen.findByText('The registration period cannot be changed for a cancelled or ended event.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Registration opens (SGT)')).not.toBeInTheDocument()

    unmount()
    const ended = new Date(Date.now() - 60_000).toISOString()
    vi.mocked(apiClient.get).mockResolvedValue(base)
    render(<OrganiserRegistrations eventId="event-1" eventStartDatetime={eventStart}
      eventEndDatetime={ended} eventStatus="confirmed" />)
    expect(await screen.findByText('The registration period cannot be changed for a cancelled or ended event.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Registration opens (SGT)')).not.toBeInTheDocument()
  })
})
