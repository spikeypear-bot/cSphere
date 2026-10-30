import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/apiClient'
import { OrganiserRegistrations } from './OrganiserRegistrations'

vi.mock('../../lib/apiClient', () => ({ apiClient: { get: vi.fn(), put: vi.fn() }, ApiClientError: class extends Error {} }))

const base = {
  eventId: 'e1', eventName: 'Fair', registrationRequired: true, registrationState: 'open', capacity: 5,
  confirmedCount: 1, placesRemaining: 4, opensAt: null, closesAt: null, message: null,
  attendees: [
    { registrationId: 'r1', name: 'Ann', reference: 'R-1', registeredAt: '2027-01-01T00:00:00Z', status: 'confirmed' },
    { registrationId: 'r2', name: 'Bob', reference: 'R-2', registeredAt: '2027-01-01T00:00:00Z', status: 'withdrawn' },
  ],
}

// EO10
describe('OrganiserRegistrations (EO10)', () => {
  beforeEach(() => vi.mocked(apiClient.get).mockReset())
  afterEach(cleanup)

  it('EO10 AC2-AC4: shows counts and distinguishes statuses', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(base)
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByText('Ann')).toBeInTheDocument()
    expect(screen.getByText('Places remaining')).toBeInTheDocument()
    expect(screen.getByText('Withdrawn')).toBeInTheDocument()
    expect(screen.getByText('Active (confirmed)')).toBeInTheDocument()
  })

  it('EO10 AC6: shows a message when registration is not required', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      ...base, registrationRequired: false, registrationState: 'not_required', attendees: [],
      message: 'Registration is not required for this event.',
    })
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByText('Registration is not required for this event.')).toBeInTheDocument()
  })

  it('EO10 AC9: shows an error with retry', async () => {
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('x')).mockResolvedValueOnce(base)
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.getByText('Ann')).toBeInTheDocument())
  })

  it('EO11: rejects invalid capacity without calling the API', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(base)
    render(<OrganiserRegistrations eventId="e1" />)
    const input = await screen.findByLabelText('Registration capacity')
    for (const bad of ['0', '-2', '2.5', 'abc', '']) {
      await userEvent.clear(input)
      if (bad) await userEvent.type(input, bad)
      await userEvent.click(screen.getByRole('button', { name: 'Save capacity' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('positive whole number')
    }
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('EO11: saves and shows the updated capacity', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(base)
    vi.mocked(apiClient.put).mockResolvedValue({ ...base, capacity: 9, placesRemaining: 8 })
    render(<OrganiserRegistrations eventId="e1" />)
    await userEvent.type(await screen.findByLabelText('Registration capacity'), '9')
    await userEvent.click(screen.getByRole('button', { name: 'Save capacity' }))
    expect(apiClient.put).toHaveBeenCalledWith('/events/e1/registration-capacity', { capacity: 9 })
    expect(await screen.findByText('Capacity saved: 9 places.')).toBeInTheDocument()
  })

  it('EO11: shows an error and keeps the old capacity when saving fails', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(base)
    vi.mocked(apiClient.put).mockRejectedValue(new Error('boom'))
    render(<OrganiserRegistrations eventId="e1" />)
    await userEvent.type(await screen.findByLabelText('Registration capacity'), '9')
    await userEvent.click(screen.getByRole('button', { name: 'Save capacity' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('existing capacity is unchanged')
    expect(screen.getByText('Places remaining').previousSibling).toHaveTextContent('4')
  })
})
