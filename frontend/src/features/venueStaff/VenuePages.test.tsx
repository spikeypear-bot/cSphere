// @stories DEV16 VS07 VS06A VS06B VS17  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { VenueEditPage } from './VenueEditPage'
import { VenueCreatePage } from './VenueCreatePage'
import { VenueCataloguePage } from './VenueCataloguePage'
import { apiClient } from '../../lib/apiClient'

const venue = { venueId: '123', venueAddress: 'Example Road', venueCapacity: 50,
  supportedLayouts: ['theatre'], operatingInformation: 'Monday 9-5', additionalInformation: null,
  venueAccessibilities: [], venueFacilities: [], operatingHours: [{dayOfWeek:1,openTime:'09:00',closeTime:'17:00'}] }
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
function page(path = '/venue-staff/catalogue/new') {
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/venue-staff/catalogue/:venueId/edit" element={<VenueEditPage />} />
    <Route path="/venue-staff/catalogue/new" element={<VenueCreatePage />} />
    <Route path="/venue-staff/catalogue" element={<VenueCataloguePage />} />
  </Routes></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('defaults capacity to 50 and rejects missing fields without POST', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); page()
  expect(screen.getByLabelText('Overall capacity')).toHaveValue(50)
  await userEvent.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getAllByRole('alert')).toHaveLength(4)
  expect(screen.getByText('Enter the venue name.')).toBeInTheDocument()
  expect(screen.getByText('Enter the venue address.')).toBeInTheDocument()
  expect(screen.getByText('Select at least one layout.')).toBeInTheDocument()
  expect(screen.getByText('Select at least one operating day.')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
it.each(['-1', '0', '50001', '1.5'])('rejects capacity %s', async capacity => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); page()
  const user = userEvent.setup()
  await user.clear(screen.getByLabelText('Overall capacity'))
  await user.type(screen.getByLabelText('Overall capacity'), capacity)
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getByText('Enter a whole number from 1 to 50,000.')).toBeInTheDocument()
  expect(screen.getAllByRole('alert')).toHaveLength(5)
  expect(screen.getByText('Enter the venue name.')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
it('shows only the edited invalid field and clears its error when corrected', async () => {
  page()
  const user = userEvent.setup()
  expect(screen.getByLabelText('Venue address')).toHaveAttribute('placeholder')
  expect(screen.queryByLabelText('Operating information')).not.toBeInTheDocument()
  expect(screen.getByRole('checkbox', {name:'Monday'})).toBeInTheDocument()
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
  await user.type(screen.getByLabelText('Venue name'), 'Example Hall')
  await user.type(screen.getByLabelText('Venue address'), ' Example Road ')
  await user.click(screen.getByRole('button', { name: 'Theatre' }))
  await user.click(screen.getByRole('checkbox', {name:'Monday'}))
  fireEvent.change(screen.getByLabelText('Monday closes'), {target:{value:'17:00'}})
  return user
}

describe('DEV16 venue form coverage', () => {
  it('DEV16 (boundary): a legacy empty address is labelled and cannot be saved as a valid venue', () => {
    const put = vi.spyOn(apiClient, 'put')
    render(<MemoryRouter><VenueCreatePage initialVenue={{ ...venue, supportedLayouts: ['theatre'], venueAddress: '' }} /></MemoryRouter>)
    expect(screen.getByText('Not recorded')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save venue' }))
    expect(put).not.toHaveBeenCalled()
  })
  it.each([false, true])('DEV16 (cross-cutting): leaving the venue editor ignores late read results (%s)', async failed => {
    let resolve!: (value: unknown) => void, reject!: (cause: unknown) => void
    const read = vi.spyOn(apiClient, 'get').mockReturnValue(new Promise((done, fail) => { resolve = done; reject = fail }))
    const pageView = page('/venue-staff/catalogue/123/edit')
    expect(screen.getByRole('status')).toHaveTextContent('Loading venue…')
    pageView.unmount()
    if (failed) reject(new Error('Offline')); else resolve(venue)
    await Promise.resolve()
    expect(read).toHaveBeenCalledExactlyOnceWith('/venues/123')
    expect(screen.queryByRole('button', { name: 'Save venue' })).not.toBeInTheDocument()
  })
  it('DEV16 (negative): overlong identity fields and invalid default durations prevent writing', async () => {
    const post = vi.spyOn(apiClient, 'post')
    page(); await fillForm()
    fireEvent.change(screen.getByLabelText('Venue name'), { target: { value: 'x'.repeat(201) } })
    fireEvent.change(screen.getByLabelText('Venue address'), { target: { value: 'x'.repeat(501) } })
    fireEvent.change(screen.getByLabelText('Default setup (minutes)'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Default turnaround (minutes)'), { target: { value: '-1' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Save venue' }).closest('form')!)
    expect(screen.getByText('Use 200 characters or fewer.')).toBeInTheDocument()
    expect(screen.getByText('Use 500 characters or fewer.')).toBeInTheDocument()
    expect(screen.getAllByText('Enter whole minutes from 0 to 10,080.')).toHaveLength(2)
    expect(post).not.toHaveBeenCalled()
  })
  it('DEV16 (happy): editing one of several operating days preserves the other and a closed day is removed', async () => {
    const post = vi.spyOn(apiClient, 'post').mockReturnValue(new Promise(() => {}))
    page(); await fillForm()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Tuesday' }))
    fireEvent.change(screen.getByLabelText('Monday opens'), { target: { value: '08:30' } })
    fireEvent.change(screen.getByLabelText('Monday closes'), { target: { value: '19:00' } })
    expect(screen.getByLabelText('Tuesday opens')).toHaveValue('09:00')
    expect(screen.getByLabelText('Tuesday closes')).toHaveValue('18:00')
    fireEvent.click(screen.getByRole('checkbox', { name: 'Tuesday' }))
    expect(screen.queryByLabelText('Tuesday opens')).not.toBeInTheDocument()
    const form = screen.getByRole('button', { name: 'Save venue' }).closest('form')!
    fireEvent.submit(form); fireEvent.submit(form)
    expect(post).toHaveBeenCalledExactlyOnceWith('/venues', expect.objectContaining({
      operatingHours: [{ dayOfWeek: 1, openTime: '08:30', closeTime: '19:00' }],
    }))
  })
  it('DEV16 (failure): unexpected creation failures preserve the completed form', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue(new Error('Offline'))
    page(); await fillForm()
    fireEvent.click(screen.getByRole('button', { name: 'Save venue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the venue. Please try again.')
    expect(screen.getByLabelText('Venue name')).toHaveValue('Example Hall')
    expect(screen.getByRole('button', { name: 'Save venue' })).toBeEnabled()
  })
  it('DEV16 (failure): unexpected edit-load failures offer retry and recover the saved venue', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValueOnce(new Error('Offline')).mockResolvedValue(venue)
    page('/venue-staff/catalogue/123/edit')
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the venue. Please try again.')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Edit Venue' })).toBeInTheDocument()
    expect(screen.getByLabelText('Overall capacity')).toHaveValue(50)
  })
})
it('POSTs trimmed input then GETs the catalogue and displays success', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response(venue, 201)).mockResolvedValueOnce(response([venue]))
  vi.stubGlobal('fetch', fetch); page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Venue saved successfully.')).toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: 'Example Road' })).toBeInTheDocument()
  expect(screen.getByLabelText('Venue ID')).toHaveTextContent(venue.venueId)
  expect(screen.getByText(venue.venueId)).toBeInTheDocument()
  expect(fetch.mock.calls[0][0]).toBe('/api/venues')
  expect(fetch.mock.calls[0][1].method).toBe('POST')
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ venueName: 'Example Hall', venueAddress: 'Example Road', venueCapacity: 50,
    supportedLayouts: ['theatre'], additionalInformation: null,
    venueAccessibilities: [], venueFacilities: [], operatingHours: [{dayOfWeek:1,openTime:'09:00',closeTime:'17:00'}], setupMinutes: 0, turnaroundMinutes: 0 })
  expect(fetch.mock.calls[1][1].method).toBe('GET')
  await user.click(screen.getByRole('button', { name: 'Dismiss' }))
  expect(screen.queryByText('Venue saved successfully.')).not.toBeInTheDocument()
})
it('retains input after backend rejection', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: 'Invalid venue details' }, 422)))
  page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Elevators' }))
  await user.click(screen.getByRole('button', { name: 'Stage' }))
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid venue details')
  expect(screen.getByLabelText('Venue address')).toHaveValue(' Example Road ')
  expect(screen.getByRole('button', { name: 'Elevators' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Stage' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Save venue' })).toBeEnabled()
})

it('saves selections and displays the freshly fetched catalogue labels', async () => {
  const saved = { ...venue, venueAccessibilities: ['step_free_access', 'elevators'], venueFacilities: ['projection', 'stage'] }
  const fetch = vi.fn().mockResolvedValueOnce(response(saved, 201))
    .mockResolvedValueOnce(response([{ ...saved, additionalInformation: 'Fresh server details' }]))
  vi.stubGlobal('fetch', fetch); page(); const user = await fillForm()
  for (const name of ['Step-free access', 'Elevators', 'Projection', 'Stage']) {
    await user.click(screen.getByRole('button', { name }))
  }
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Step-free access, Elevators')).toBeInTheDocument()
  expect(screen.getByText('Projection, Stage')).toBeInTheDocument()
  expect(screen.getByText('Fresh server details')).toBeInTheDocument()
  expect(screen.getByText('50 people')).toBeInTheDocument()
  expect(screen.getByText('Theatre')).toBeInTheDocument()
  expect(screen.getByText('9am \u2013 5pm')).toBeInTheDocument()
  expect(screen.queryByText('Monday 9-5')).not.toBeInTheDocument()
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({
    venueAccessibilities: saved.venueAccessibilities, venueFacilities: saved.venueFacilities,
  })
  expect(fetch.mock.calls[1][0]).toBe('/api/venues')
  expect(fetch.mock.calls[1][1].method).toBe('GET')
})

it('keeps no provisions exclusive and lets selections be cleared', async () => {
  page(); const user = userEvent.setup()
  const none = screen.getByRole('button', { name: 'No accessibility provisions' })
  const elevators = screen.getByRole('button', { name: 'Elevators' })
  await user.click(elevators)
  await user.click(none)
  expect(none).toHaveAttribute('aria-pressed', 'true')
  expect(elevators).toHaveAttribute('aria-pressed', 'false')
  await user.click(elevators)
  expect(none).toHaveAttribute('aria-pressed', 'false')
  expect(elevators).toHaveAttribute('aria-pressed', 'true')
  await user.click(elevators)
  await user.click(none)
  await user.click(none)
  expect(none).toHaveAttribute('aria-pressed', 'false')
  const stage = screen.getByRole('button', { name: 'Stage' })
  await user.click(stage)
  await user.click(stage)
  expect(stage).toHaveAttribute('aria-pressed', 'false')
})

it('distinguishes empty selections from explicitly no accessibility provisions', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([
    venue, { ...venue, venueId: '456', venueAddress: 'Second venue', venueAccessibilities: ['none'] },
  ])))
  page('/venue-staff/catalogue')
  expect(await screen.findByText('No accessibility provisions')).toBeInTheDocument()
  expect(screen.getAllByText('Not recorded')).toHaveLength(3)
  expect(screen.getAllByText('No additional information recorded.')).toHaveLength(2)
})
it('handles network failure and retries catalogue loading', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error()).mockResolvedValueOnce(response([])))
  page('/venue-staff/catalogue')
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach ConnectSphere')
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('No venues yet')).toBeInTheDocument()
})

it('saves explicit no provisions as none and displays the saved meaning', async () => {
  const saved = { ...venue, venueAccessibilities: ['none'] }
  const fetch = vi.fn().mockResolvedValueOnce(response(saved, 201)).mockResolvedValueOnce(response([saved]))
  vi.stubGlobal('fetch', fetch); page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Elevators' }))
  await user.click(screen.getByRole('button', { name: 'No accessibility provisions' }))
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Venue saved successfully.')).toBeInTheDocument()
  expect(await screen.findByText('No accessibility provisions')).toBeInTheDocument()
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ venueAccessibilities: ['none'], venueFacilities: [] })
})

it.each(['capacity', 'layouts', 'operating hours'])('still validates %s when provisions are selected', async field => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  page(); const user = await fillForm()
  await user.click(screen.getByRole('button', { name: 'Elevators' }))
  await user.click(screen.getByRole('button', { name: 'Stage' }))
  if (field === 'capacity') {
    await user.clear(screen.getByLabelText('Overall capacity'))
    await user.type(screen.getByLabelText('Overall capacity'), '0')
  } else if (field === 'layouts') {
    await user.click(screen.getByRole('button', { name: 'Theatre' }))
  } else {
    await user.click(screen.getByRole('checkbox', {name:'Monday'}))
  }
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getByRole('alert')).toHaveTextContent(field === 'capacity' ? 'Enter a whole number'
    : field === 'layouts' ? 'Select at least one layout' : 'Select at least one operating day')
  expect(fetch).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Elevators' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Stage' })).toHaveAttribute('aria-pressed', 'true')
})

it('displays all supported provision and facility labels', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([{ ...venue,
    venueAccessibilities: ['accessible_parking', 'drop_off_zone', 'public_transport', 'step_free_access',
      'wide_doorways', 'elevators', 'wheelchair_support'],
    venueFacilities: ['audio_visual_equipment', 'air_conditioning', 'breakout_spaces', 'projection',
      'stage', 'dining_area', 'barbeque_pit'],
  }])))
  page('/venue-staff/catalogue')
  expect(await screen.findByText('Accessible parking, Drop-off zone, Near public transport, Step-free access, Wide doorways, Elevators, Wheelchair support')).toBeInTheDocument()
  expect(screen.getByText('Audio-visual equipment, Air conditioning, Breakout spaces, Projection, Stage, Dining area, Barbeque pit')).toBeInTheDocument()
})


it.each(['capacity', 'layouts', 'accessibility', 'facilities', 'hours', 'additional'])('edits %s and reloads saved data', async field => {
  let saved = { ...venue, venueCapacity: 100, supportedLayouts: ['classroom'],
    venueAccessibilities: ['step_free_access'], venueFacilities: ['projection'] }
  const fetch = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (options.method === 'PUT') saved = { ...saved, ...JSON.parse(options.body as string) }
    return response(url.endsWith('/venues') ? [saved] : saved)
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/catalogue')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: 'Edit venue' }))
  expect(await screen.findByLabelText('Overall capacity')).toHaveValue(100)
  expect(screen.queryByLabelText('Venue address')).not.toBeInTheDocument()
  expect(screen.getByLabelText('Additional information (optional)')).toHaveValue('')
  expect(screen.getByRole('button', { name: 'Classroom' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Step-free access' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Projection' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByLabelText('Monday opens')).toHaveValue('09:00')
  expect(screen.getByLabelText('Monday closes')).toHaveValue('17:00')
  if (field === 'capacity') {
    await user.clear(screen.getByLabelText('Overall capacity'))
    await user.type(screen.getByLabelText('Overall capacity'), '150')
  } else if (field === 'additional') {
    await user.type(screen.getByLabelText('Additional information (optional)'), 'Use Level 2')
  } else if (field === 'hours') {
    fireEvent.change(screen.getByLabelText('Monday closes'), {target:{value:'18:00'}})
  } else {
    await user.click(screen.getByRole('button', { name: field === 'layouts' ? 'Theatre' : field === 'accessibility' ? 'Elevators' : 'Stage' }))
  }
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Venue updated successfully.')).toBeInTheDocument()
  expect(await screen.findByText(field === 'capacity' ? '150 people' : '100 people')).toBeInTheDocument()
  const update = fetch.mock.calls.find(call => call[1].method === 'PUT')!
  expect(update[0]).toContain('/venues/123')
  expect(Object.keys(JSON.parse(update[1].body))).toEqual([({ capacity: 'venueCapacity', layouts: 'supportedLayouts', accessibility: 'venueAccessibilities', facilities: 'venueFacilities', hours: 'operatingHours', additional: 'additionalInformation' })[field]])
  await user.click(screen.getByRole('link', { name: 'Edit venue' }))
  expect(await screen.findByLabelText('Overall capacity')).toHaveValue(saved.venueCapacity)
  expect(screen.getByLabelText('Monday opens')).toHaveValue(saved.operatingHours[0].openTime)
  expect(screen.getByLabelText('Monday closes')).toHaveValue(saved.operatingHours[0].closeTime)
  expect(screen.getByLabelText('Additional information (optional)')).toHaveValue(saved.additionalInformation ?? '')
  for (const name of ['Classroom', 'Step-free access', 'Projection', ...(field === 'layouts' ? ['Theatre'] : field === 'accessibility' ? ['Elevators'] : field === 'facilities' ? ['Stage'] : [])]) {
    expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'true')
  }
})

it('shows edit validation and server rejection without losing input', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response(venue)).mockResolvedValueOnce(response({ message: 'venueCapacity must be between 1 and 50000' }, 422))
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/catalogue/123/edit')
  const user = userEvent.setup()
  await user.clear(await screen.findByLabelText('Overall capacity'))
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(screen.getByRole('alert')).toHaveTextContent('Enter a whole number')
  expect(fetch).toHaveBeenCalledTimes(1)
  await user.type(screen.getByLabelText('Overall capacity'), '150')
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('venueCapacity must be between')
  expect(screen.getByLabelText('Overall capacity')).toHaveValue(150)
  expect(screen.getByRole('button', { name: 'Theatre' })).toHaveAttribute('aria-pressed', 'true')
})

it('shows loading errors and retries before enabling editing', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ message: 'Venue not found' }, 404)).mockResolvedValueOnce(response(venue))
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/catalogue/123/edit')
  expect(await screen.findByRole('alert')).toHaveTextContent('Venue not found')
  expect(screen.queryByRole('button', { name: 'Save venue' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByLabelText('Overall capacity')).toHaveValue(50)
})


it('saves multiple changes together, clears additional information and reopens current values', async () => {
  let saved = { ...venue, additionalInformation: 'Use Level 2' }
  const fetch = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (options.method === 'PUT') saved = { ...saved, ...JSON.parse(options.body as string) }
    return response(url.endsWith('/venues') ? [saved] : saved)
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/catalogue/123/edit')
  const user = userEvent.setup()
  expect(await screen.findByLabelText('Additional information (optional)')).toHaveValue('Use Level 2')
  await user.clear(screen.getByLabelText('Overall capacity'))
  await user.type(screen.getByLabelText('Overall capacity'), '150')
  fireEvent.change(screen.getByLabelText('Monday closes'), {target:{value:'18:00'}})
  await user.clear(screen.getByLabelText('Additional information (optional)'))
  await user.click(screen.getByRole('button', { name: 'Save venue' }))
  expect(await screen.findByText('Venue updated successfully.')).toBeInTheDocument()
  expect(await screen.findByText('150 people')).toBeInTheDocument()
  expect(screen.getByText('9am \u2013 6pm')).toBeInTheDocument()
  expect(screen.getByText('No additional information recorded.')).toBeInTheDocument()
  expect(JSON.parse(fetch.mock.calls.find(call => call[1].method === 'PUT')![1].body)).toEqual({
    venueCapacity: 150, operatingHours: [{dayOfWeek:1,openTime:'09:00',closeTime:'18:00'}], additionalInformation: '',
  })
  await user.click(screen.getByRole('link', { name: 'Edit venue' }))
  expect(await screen.findByLabelText('Overall capacity')).toHaveValue(150)
  expect(screen.getByLabelText('Monday closes')).toHaveValue('18:00')
  expect(screen.getByLabelText('Additional information (optional)')).toHaveValue('')
  expect(screen.getByRole('button', { name: 'Theatre' })).toHaveAttribute('aria-pressed', 'true')
})

it.each(['', '09:00', '08:30'])('VS06A (negative): missing, equal or reversed closing time %s blocks creation without a request', async close => {
 const fetch=vi.fn();vi.stubGlobal('fetch',fetch);page();const user=await fillForm()
 fireEvent.change(screen.getByLabelText('Monday closes'),{target:{value:close}})
 await user.click(screen.getByRole('button',{name:'Save venue'}))
 expect(screen.getByRole('alert')).toHaveTextContent('Closing times must be after opening times.')
 expect(fetch).not.toHaveBeenCalled()
 expect(screen.getByRole('checkbox',{name:'Monday'})).toBeChecked()
 expect(screen.getByLabelText('Monday opens')).toHaveValue('09:00')
})
it('VS07 (failure): a failed hours update retains both edited times and excludes legacy text from the update',async()=>{
 const fetch=vi.fn(async (_:unknown,init?:RequestInit)=>init?.method==='PUT' ? response({message:'Save unavailable'},503) : response(venue))
 vi.stubGlobal('fetch',fetch);page('/venue-staff/catalogue/123/edit')
 await screen.findByLabelText('Monday opens')
 fireEvent.change(screen.getByLabelText('Monday opens'),{target:{value:'10:30'}})
 fireEvent.change(screen.getByLabelText('Monday closes'),{target:{value:'23:30'}})
 await userEvent.click(screen.getByRole('button',{name:'Save venue'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('Save unavailable')
 expect(screen.getByLabelText('Monday opens')).toHaveValue('10:30')
 expect(screen.getByLabelText('Monday closes')).toHaveValue('23:30')
 expect(JSON.parse(String(fetch.mock.calls.find(c=>c[1]?.method==='PUT')?.[1]?.body))).toEqual({operatingHours:[{dayOfWeek:1,openTime:'10:30',closeTime:'23:30'}]})
 expect(screen.queryByLabelText('Operating information')).not.toBeInTheDocument()
})
