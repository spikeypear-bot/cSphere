// @stories EO10 EO11
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiClientError } from '../../lib/apiClient'
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
describe('EO10 EO11 OrganiserRegistrations', () => {
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
  it('EO10 (happy): shows ten attendees per page and allows forward and backward navigation', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, attendees: Array.from({ length: 21 }, (_, index) => ({
      ...base.attendees[0], registrationId: `r${index}`, name: `Attendee ${index + 1}`, reference: `REF-${index + 1}`,
    })) })
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByText('Attendee 1')).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(11)
    expect(screen.queryByText('Attendee 11')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Attendee 11')).toBeInTheDocument()
    expect(screen.queryByText('Attendee 1')).not.toBeInTheDocument()
    expect(screen.getByText(/Showing 11–20 of 21 attendees/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(screen.getByText('Attendee 21')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByText('Attendee 11')).toBeInTheDocument()
  })

  it.each([0, 10])('EO10 (boundary): hides pagination for %s attendees', async (count) => {
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, attendees: Array.from({ length: count }, (_, index) => ({
      ...base.attendees[0], registrationId: `r${index}`, name: `Attendee ${index + 1}`,
    })) })
    render(<OrganiserRegistrations eventId="e1" />)
    await screen.findByRole('heading', { name: 'Registration information' })
    expect(screen.queryByRole('navigation', { name: 'Registered attendees pages' })).not.toBeInTheDocument()
    if (count) expect(screen.getAllByRole('row')).toHaveLength(11)
    else expect(screen.getByText('No registrations yet.')).toBeInTheDocument()
  })

  it('EO11 (failure): shows the reason the server gives for refusing a capacity', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(base)
    vi.mocked(apiClient.put).mockRejectedValue(new ApiClientError('Capacity cannot be below the 1 confirmed registration.', 409))
    render(<OrganiserRegistrations eventId="e1" />)
    await userEvent.type(await screen.findByLabelText('Registration capacity'), '9')
    await userEvent.click(screen.getByRole('button', { name: 'Save capacity' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Capacity cannot be below the 1 confirmed registration.')
  })

  it('EO10 (boundary): an event with no capacity limit shows unlimited places and no fill bar', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, capacity: null, placesRemaining: null })
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByText('Capacity')).toBeInTheDocument()
    expect(screen.getByText('Capacity').previousSibling).toHaveTextContent('∞')
    expect(screen.getByText('Places remaining').previousSibling).toHaveTextContent('∞')
    expect(screen.queryByRole('img', { name: /of places taken/ })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Registration capacity')).toHaveAttribute('placeholder', 'e.g. 50')
  })

  it.each([
    [8, '80% of places taken', 'ok'],
    [9, '90% of places taken', 'near'],
    [10, '100% of places taken', 'full'],
    [12, '100% of places taken', 'full'],
  ])('EO10 (boundary): %s of 10 places taken reads "%s" and the bar is %s', async (confirmedCount, label, state) => {
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, capacity: 10, confirmedCount })
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByRole('img', { name: label })).toHaveClass(`registrations__bar--${state}`)
  })

  it('EO10 (boundary): a registration status this page has no label for is shown by its name', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, attendees: [{ ...base.attendees[0], status: 'waitlisted' }] })
    render(<OrganiserRegistrations eventId="e1" />)
    expect(await screen.findByText('waitlisted')).toBeInTheDocument()
  })

  it.each(['answers', 'fails'])('EO10 (conflict): when the previous event\'s read %s late, the event now shown is unchanged', async (outcome) => {
    let settleFirst!: () => void
    vi.mocked(apiClient.get).mockImplementation((path: string) => path === '/events/e1/registrations'
      ? new Promise((resolve, reject) => {
        settleFirst = () => (outcome === 'answers' ? resolve({ ...base, eventName: 'Fair' }) : reject(new Error('late')))
      })
      : Promise.resolve({ ...base, eventId: 'e2', eventName: 'Second Fair' }))
    const { rerender } = render(<OrganiserRegistrations eventId="e1" />)
    rerender(<OrganiserRegistrations eventId="e2" />)
    expect(await screen.findByText('Second Fair')).toBeInTheDocument()
    await act(async () => settleFirst())
    expect(screen.getByText('Second Fair')).toBeInTheDocument()
    expect(screen.queryByText('Fair')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('EO10 (boundary): switching to another event starts its attendee list on the first page', async () => {
    const attendees = Array.from({ length: 11 }, (_, index) => ({
      ...base.attendees[0], registrationId: `r${index}`, name: `Attendee ${index + 1}`,
    }))
    vi.mocked(apiClient.get).mockResolvedValue({ ...base, attendees })
    const { rerender } = render(<OrganiserRegistrations eventId="e1" />)
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }))
    expect(screen.getByText('Attendee 11')).toBeInTheDocument()
    rerender(<OrganiserRegistrations eventId="e2" />)
    expect(await screen.findByText(/Page 1 of 2/)).toBeInTheDocument()
    expect(screen.getByText('Attendee 1')).toBeInTheDocument()
  })

})
