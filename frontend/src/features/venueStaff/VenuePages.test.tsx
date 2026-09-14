import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { VenueCreatePage } from './VenueCreatePage'
import { VenueCataloguePage } from './VenueCataloguePage'

const venue = { venueId: '123', venueAddress: 'Example Road', venueCapacity: 50,
  supportedLayouts: ['theatre'], operatingInformation: 'Monday 9-5', additionalInformation: null }
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
function page(path = '/venue-staff/catalogue/new') {
  render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/venue-staff/catalogue/new" element={<VenueCreatePage />} />
    <Route path="/venue-staff/catalogue" element={<VenueCataloguePage />} />
  </Routes></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('defaults capacity to 50 and rejects missing fields without POST', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); page()
  expect(screen.getByLabelText('Overall capacity')).toHaveValue(50)
  await userEvent.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getAllByRole('alert')).toHaveLength(3)
  expect(screen.getByText('Enter the venue address.')).toBeInTheDocument()
  expect(screen.getByText('Select at least one layout.')).toBeInTheDocument()
  expect(screen.getByText('Enter operating days and hours.')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
it.each(['-1', '0', '50001', '1.5'])('rejects capacity %s', async capacity => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); page()
  const user = userEvent.setup()
  await user.clear(screen.getByLabelText('Overall capacity'))
  await user.type(screen.getByLabelText('Overall capacity'), capacity)
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getByText('Enter a whole number from 1 to 50,000.')).toBeInTheDocument()
  expect(screen.getAllByRole('alert')).toHaveLength(4)
  expect(fetch).not.toHaveBeenCalled()
})
it('shows only the edited invalid field and clears its error when corrected', async () => {
  page()
  const user = userEvent.setup()
  expect(screen.getByLabelText('Venue address')).toHaveAttribute('placeholder')
  expect(screen.getByLabelText('Operating information')).toHaveAttribute('placeholder')
  expect(screen.getByLabelText('Additional information (optional)')).toHaveAttribute('placeholder')
  await user.type(screen.getByLabelText('Venue address'), 'Example Road')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  await user.clear(screen.getByLabelText('Overall capacity'))
  await user.type(screen.getByLabelText('Overall capacity'), '50001')
  expect(screen.getAllByRole('alert')).toHaveLength(1)
  expect(screen.getByRole('alert')).toHaveTextContent('Enter a whole number')
  await user.clear(screen.getByLabelText('Overall capacity'))
  await user.type(screen.getByLabelText('Overall capacity'), '50')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
async function fillForm() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Venue address'), ' Example Road ')
  await user.click(screen.getByRole('button', { name: 'Theatre' }))
  await user.type(screen.getByLabelText('Operating information'), 'Monday 9-5')
  return user
}
it('POSTs trimmed input then GETs the catalogue and displays success', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response(venue, 201)).mockResolvedValueOnce(response([venue]))
  vi.stubGlobal('fetch', fetch); page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Venue saved successfully.')).toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: 'Example Road' })).toBeInTheDocument()
  expect(screen.getByText('Venue ID')).toBeInTheDocument()
  expect(screen.getByText(venue.venueId)).toBeInTheDocument()
  expect(fetch.mock.calls[0][0]).toBe('/api/venues')
  expect(fetch.mock.calls[0][1].method).toBe('POST')
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ venueAddress: 'Example Road', venueCapacity: 50,
    supportedLayouts: ['theatre'], operatingInformation: 'Monday 9-5', additionalInformation: null })
  expect(fetch.mock.calls[1][1].method).toBe('GET')
  await user.click(screen.getByRole('button', { name: 'Dismiss' }))
  expect(screen.queryByText('Venue saved successfully.')).not.toBeInTheDocument()
})
it('retains input after backend rejection', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: 'Invalid venue details' }, 422)))
  page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid venue details')
  expect(screen.getByLabelText('Venue address')).toHaveValue(' Example Road ')
  expect(screen.getByRole('button', { name: 'Save venue' })).toBeEnabled()
})
it('handles network failure and retries catalogue loading', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error()).mockResolvedValueOnce(response([])))
  page('/venue-staff/catalogue')
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach ConnectSphere')
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('No venues yet')).toBeInTheDocument()
})
