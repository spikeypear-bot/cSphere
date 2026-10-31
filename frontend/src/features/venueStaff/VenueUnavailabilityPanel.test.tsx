// @stories DEV16 VS01 VS-C1 VS17  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { VenueUnavailabilityPanel, VenueOccupancySettings } from './VenueUnavailabilityPanel'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { singaporeToday } from './unavailabilityValidation'

vi.mock('../../lib/apiClient', async importOriginal => {
  const actual = await importOriginal<typeof import('../../lib/apiClient')>()
  return { ...actual, apiClient: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), put: vi.fn() } }
})
afterEach(() => { cleanup(); vi.resetAllMocks(); vi.useRealTimers() })
const booking = { bookingId: 'b1', eventName: 'Workshop', status: 'approved',
  startDateTime: '2027-04-01T10:00:00+08:00', endDateTime: '2027-04-01T12:00:00+08:00',
  effectiveStart: '2027-04-01T09:30:00+08:00', effectiveEnd: '2027-04-01T12:45:00+08:00' }
function mount() {
  vi.mocked(apiClient.get).mockResolvedValue([])
  const recorded = vi.fn()
  render(<VenueUnavailabilityPanel venueId="v1" onRecorded={recorded} />)
  return recorded
}
function fill() {
  fireEvent.change(screen.getByLabelText('Start date/time'), { target: { value: '2027-04-01T09:20' } })
  fireEvent.change(screen.getByLabelText('End date/time'), { target: { value: '2027-04-01T10:00' } })
  fireEvent.change(screen.getByLabelText('Reason'), { target: { value: ' Maintenance ' } })
}
it('VS17 (happy): unavailability preview identifies same-name bookings by event and booking IDs', async () => {
  mount();fill()
  vi.mocked(apiClient.post).mockResolvedValue({ previewToken: 'token', affectedBookings: [
    { ...booking, eventId: 'event-one' }, { ...booking, eventId: 'event-two', bookingId: 'b2' }] })
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  const preview = await screen.findByRole('alert', { name: 'Affected bookings' })
  expect(preview).toHaveTextContent('Event ID: event-one · Booking ID: b1')
  expect(preview).toHaveTextContent('Event ID: event-two · Booking ID: b2')
  expect(apiClient.post).toHaveBeenCalledTimes(1)
})
it('rejects missing and equal timestamps near the relevant fields', async () => {
  mount()
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  expect(screen.getByText('Reason is required.')).toBeInTheDocument()
  expect(screen.getByText('Start date/time is required.')).toBeInTheDocument()
  fill()
  fireEvent.change(screen.getByLabelText('End date/time'), { target: { value: '2027-04-01T09:20' } })
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  expect(screen.getAllByText('End date/time must be strictly later than start.')).toHaveLength(2)
  expect(screen.getByRole('button', { name: 'Record Unavailability' })).toBeDisabled()
  expect(apiClient.post).not.toHaveBeenCalled()
})
it('updates the end minimum, retains invalid dates, and clears live errors when corrected', () => {
  mount()
  const start = screen.getByLabelText('Start date/time')
  const end = screen.getByLabelText('End date/time')
  const submit = screen.getByRole('button', { name: 'Record Unavailability' })
  fireEvent.change(start, { target: { value: '2027-09-22T09:00' } })
  fireEvent.change(end, { target: { value: '2027-09-23T09:00' } })
  expect(start).toHaveAttribute('min', `${singaporeToday()}T00:00`)
  expect(end).toHaveAttribute('min', '2027-09-22T09:00')
  expect(submit).toBeEnabled()
  fireEvent.change(start, { target: { value: '2027-09-24T09:00' } })
  expect(end).toHaveValue('2027-09-23T09:00')
  expect(end).toHaveAttribute('min', '2027-09-24T09:00')
  expect(start).toHaveAttribute('aria-invalid', 'true')
  expect(end).toHaveAttribute('aria-invalid', 'true')
  expect(submit).toBeDisabled()
  fireEvent.change(end, { target: { value: '2027-09-24T09:01' } })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(submit).toBeEnabled()
  expect(screen.getByLabelText('Reason')).toHaveAttribute('placeholder',
    'e.g. Scheduled maintenance, renovation, safety restrictions, or an internal activity.')
  expect(apiClient.post).not.toHaveBeenCalled()
})
it('requires conflict acknowledgement and allows returning without saving', async () => {
  mount(); fill()
  vi.mocked(apiClient.post).mockResolvedValue({ affectedBookings: [booking], previewToken: 'token' })
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  expect(await screen.findByText('Workshop')).toBeInTheDocument()
  expect(screen.getByText(/will not be cancelled automatically/)).toBeInTheDocument()
  expect(apiClient.post).toHaveBeenCalledTimes(1)
  await userEvent.click(screen.getByRole('button', { name: 'Return to form' }))
  expect(screen.getByLabelText('Reason')).toHaveValue(' Maintenance ')
  expect(apiClient.post).toHaveBeenCalledTimes(1)
})
it('sends Singapore timestamps and acknowledged bookings, then refreshes', async () => {
  const recorded = mount(); fill()
  vi.mocked(apiClient.post).mockResolvedValueOnce({ affectedBookings: [booking], previewToken: 'token' }).mockResolvedValueOnce({})
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Confirm recording unavailability' }))
  expect(apiClient.post).toHaveBeenLastCalledWith('/venues/v1/unavailability', {
    startDateTime: '2027-04-01T09:20:00+08:00', endDateTime: '2027-04-01T10:00:00+08:00', reason: 'Maintenance',
    previewToken: 'token', acknowledgedBookingIds: ['b1'],
  })
  expect(recorded).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('Reason')).toHaveValue('')
})
it('saves a period without conflicts after checking', async () => {
  const recorded = mount(); fill()
  vi.mocked(apiClient.post).mockResolvedValueOnce({ affectedBookings: [], previewToken: 'empty' }).mockResolvedValueOnce({})
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  await waitFor(() => expect(recorded).toHaveBeenCalledOnce())
  expect(apiClient.post).toHaveBeenCalledTimes(2)
})
it('keeps the form and requires review again when saving fails', async () => {
  const recorded = mount(); fill()
  vi.mocked(apiClient.post).mockResolvedValueOnce({ affectedBookings: [booking], previewToken: 'token' })
    .mockRejectedValueOnce(new Error('Save failed'))
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Confirm recording unavailability' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save unavailability')
  expect(screen.getByLabelText('Reason')).toHaveValue(' Maintenance ')
  expect(recorded).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: 'Confirm recording unavailability' })).not.toBeInTheDocument()
})
it('does not save when checking affected bookings fails', async () => {
  mount(); fill()
  vi.mocked(apiClient.post).mockRejectedValue(new Error('Offline'))
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not check affected bookings')
  expect(apiClient.post).toHaveBeenCalledTimes(1)
})
it.each(['preview', 'save'])('shows an overlapping-period error from %s and preserves input', async stage => {
  const recorded = mount(); fill()
  const message = 'This period overlaps an existing unavailable period for this venue. Choose dates and times outside the recorded periods.'
  if (stage === 'save') {
    vi.mocked(apiClient.post).mockResolvedValueOnce({ affectedBookings: [], previewToken: 'empty' })
  }
  vi.mocked(apiClient.post).mockRejectedValueOnce(new ApiClientError(message, 409))
  await userEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(message)
  expect(screen.getByLabelText('Start date/time')).toHaveValue('2027-04-01T09:20')
  expect(screen.getByLabelText('End date/time')).toHaveValue('2027-04-01T10:00')
  expect(screen.getByLabelText('Reason')).toHaveValue(' Maintenance ')
  expect(recorded).not.toHaveBeenCalled()
  expect(apiClient.post).toHaveBeenCalledTimes(stage === 'save' ? 2 : 1)
})

it('VS17 (conflict): affected booking previews use the attention label and retain the explicit confirmation step', async () => {
 mount();fill()
 vi.mocked(apiClient.post).mockResolvedValue({affectedBookings:[{...booking,status:'attention'}],previewToken:'token'})
 await userEvent.click(screen.getByRole('button',{name:'Record Unavailability'}))
 expect(await screen.findByText('Requires attention')).toHaveAttribute('data-booking-status','attention')
 expect(screen.getByRole('button',{name:'Confirm recording unavailability'})).toBeInTheDocument()
 expect(apiClient.post).toHaveBeenCalledTimes(1)
 expect(apiClient.post).toHaveBeenCalledWith('/venues/v1/unavailability/preview',expect.anything())
})

it('VS17 VS01 (happy): release requires a reason, preserves status semantics, and refreshes availability', async () => {
  const period = { unavailabilityId: 'u1', startDateTime: booking.startDateTime, endDateTime: booking.endDateTime, reason: 'Maintenance' }
  vi.mocked(apiClient.get).mockResolvedValueOnce([period]).mockResolvedValue([])
  vi.mocked(apiClient.patch).mockResolvedValue({ ...period, releasedAt: '2027-04-01T08:00:00Z', releaseReason: 'Reopened' })
  const recorded = vi.fn()
  render(<VenueUnavailabilityPanel venueId="v1" onRecorded={recorded} />)
  await userEvent.click(await screen.findByRole('button', { name: 'Release unavailable period' }))
  await userEvent.click(screen.getByRole('button', { name: 'Confirm release' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Explain the release')
  expect(apiClient.patch).not.toHaveBeenCalled()
  await userEvent.type(screen.getByLabelText('Reason for releasing this period'), ' Reopened ')
  await userEvent.click(screen.getByRole('button', { name: 'Confirm release' }))
  await waitFor(() => expect(recorded).toHaveBeenCalledOnce())
  expect(apiClient.patch).toHaveBeenCalledWith('/venues/v1/unavailability/u1/release', { reason: 'Reopened' })
  expect(screen.getByText(/Existing bookings remain reserved/)).toBeInTheDocument()
})

it('VS17 VS01 (failure): release failure retains the entered reason for retry', async () => {
  vi.mocked(apiClient.get).mockResolvedValue([{ unavailabilityId: 'u1', startDateTime: booking.startDateTime, endDateTime: booking.endDateTime, reason: 'Maintenance' }])
  vi.mocked(apiClient.patch).mockRejectedValue(new ApiClientError('Storage unavailable. Please retry.', 503))
  const recorded = vi.fn()
  render(<VenueUnavailabilityPanel venueId="v1" onRecorded={recorded} />)
  await userEvent.click(await screen.findByRole('button', { name: 'Release unavailable period' }))
  await userEvent.type(screen.getByLabelText('Reason for releasing this period'), 'Reopened')
  await userEvent.click(screen.getByRole('button', { name: 'Confirm release' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Storage unavailable')
  expect(screen.getByLabelText('Reason for releasing this period')).toHaveValue('Reopened')
  expect(recorded).not.toHaveBeenCalled()
})

it('VS17 VS01 (happy): staff can inspect retained release history', async () => {
  vi.mocked(apiClient.get).mockImplementation(async path => path.endsWith('/history') ? [{
    unavailabilityId: 'u1', startDateTime: booking.startDateTime, endDateTime: booking.endDateTime, reason: 'Maintenance',
    releasedAt: '2027-04-01T08:00:00Z', releaseReason: 'Repairs complete',
  }] : [])
  render(<VenueUnavailabilityPanel venueId="v1" onRecorded={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: 'View unavailability history' }))
  expect(await screen.findByText('Released: Repairs complete')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Hide unavailability history' }))
  expect(screen.queryByText('Released: Repairs complete')).not.toBeInTheDocument()
})

describe('DEV16 unavailability workflow coverage', () => {
  it('DEV16 (boundary): second-precision periods preserve their exact instants and unknown preview statuses remain visible', async () => {
    mount(); fill()
    fireEvent.change(screen.getByLabelText('Start date/time'), { target: { value: '2027-04-01T09:20:30' } })
    fireEvent.change(screen.getByLabelText('End date/time'), { target: { value: '2027-04-01T10:00:45' } })
    vi.mocked(apiClient.post).mockResolvedValue({ previewToken: 'token', affectedBookings: [{ ...booking, status: 'legacy' }] })
    fireEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
    expect(await screen.findByText('Unknown status')).toBeInTheDocument()
    expect(apiClient.post).toHaveBeenCalledExactlyOnceWith('/venues/v1/unavailability/preview', {
      startDateTime: '2027-04-01T09:20:30.000+08:00', endDateTime: '2027-04-01T10:00:45.000+08:00', reason: 'Maintenance',
    })
  })
  it('DEV16 (boundary): confirmation after Singapore midnight revalidates dates without saving', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2027-04-01T15:59:00Z'))
    mount(); fill()
    vi.mocked(apiClient.post).mockResolvedValue({ previewToken: 'token', affectedBookings: [booking] })
    fireEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
    await screen.findByRole('button', { name: 'Confirm recording unavailability' })
    vi.setSystemTime(new Date('2027-04-01T16:01:00Z'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm recording unavailability' }))
    expect(await screen.findByText('Start date cannot be before today (Singapore time).')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm recording unavailability' })).not.toBeInTheDocument()
    expect(apiClient.post).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (negative): an overlong reason is rejected without a preview request', async () => {
    mount(); fill()
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'x'.repeat(2001) } })
    fireEvent.click(screen.getByRole('button', { name: 'Record Unavailability' }))
    expect(screen.getByText('Reason must be at most 2000 characters.')).toBeInTheDocument()
    expect(apiClient.post).not.toHaveBeenCalled()
  })
  it('DEV16 (failure): history and active-period read errors can be retried independently', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Offline'))
    render(<VenueUnavailabilityPanel venueId="v1" onRecorded={vi.fn()} />)
    await screen.findByRole('button', { name: 'Retry unavailability' })
    vi.mocked(apiClient.get).mockResolvedValue([])
    fireEvent.click(screen.getByRole('button', { name: 'Retry unavailability' }))
    await screen.findByText('No unavailable periods recorded.')
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Offline'))
    fireEvent.click(screen.getByRole('button', { name: 'View unavailability history' }))
    await screen.findByRole('button', { name: 'Retry unavailability history' })
    vi.mocked(apiClient.get).mockResolvedValue([{ unavailabilityId: 'u1', reason: 'Maintenance',
      startDateTime: booking.startDateTime, endDateTime: booking.endDateTime }])
    fireEvent.click(screen.getByRole('button', { name: 'Retry unavailability history' }))
    expect(await screen.findByText('Active')).toBeInTheDocument()
  })
  it('DEV16 (boundary): empty history and cancelling a release retain the active period', async () => {
    vi.mocked(apiClient.get).mockImplementation(async path => path.endsWith('/history') ? [] : [{
      unavailabilityId: 'u1', reason: 'Maintenance', startDateTime: booking.startDateTime, endDateTime: booking.endDateTime }])
    render(<VenueUnavailabilityPanel venueId="v1" onRecorded={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Release unavailable period' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel release' }))
    expect(screen.queryByLabelText('Reason for releasing this period')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Release unavailable period' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'View unavailability history' }))
    expect(await screen.findByText('No unavailable periods recorded.')).toBeInTheDocument()
    expect(apiClient.patch).not.toHaveBeenCalled()
  })
  it('DEV16 (happy): occupancy settings load, save both durations and refresh the caller', async () => {
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Offline')).mockResolvedValue({ setupMinutes: 30, turnaroundMinutes: 15 })
    vi.mocked(apiClient.put).mockResolvedValue({})
    const saved = vi.fn()
    render(<VenueOccupancySettings venueId="v1" onSaved={saved} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Retry setup settings' }))
    await screen.findByText('Setup and turnaround settings')
    fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('Turnaround (minutes)'), { target: { value: '60' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Save setup and turnaround' }).closest('form')!)
    expect(await screen.findByRole('status')).toHaveTextContent('Setup and turnaround saved.')
    expect(apiClient.put).toHaveBeenCalledExactlyOnceWith('/venues/v1/occupancy-settings', { setupMinutes: 45, turnaroundMinutes: 60 })
    expect(saved).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (failure): failed settings save preserves the proposed durations for a retry without a callback', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ setupMinutes: 30, turnaroundMinutes: 15 })
    vi.mocked(apiClient.put).mockRejectedValueOnce(new Error('Offline')).mockResolvedValue({})
    render(<VenueOccupancySettings venueId="v1" />)
    await screen.findByText('Setup and turnaround settings')
    fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '90' } })
    const form = screen.getByRole('button', { name: 'Save setup and turnaround' }).closest('form')!
    fireEvent.submit(form)
    expect(await screen.findByRole('status')).toHaveTextContent('Could not save settings.')
    expect(screen.getByLabelText('Setup (minutes)')).toHaveValue(90)
    fireEvent.submit(form)
    expect(await screen.findByRole('status')).toHaveTextContent('Setup and turnaround saved.')
  })
})
