// @stories DEV16 VS13 VS17
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { VenueOperationalIssuesPanel } from './VenueOperationalIssuesPanel'
import { apiClient } from '../../lib/apiClient'
const json=(body:unknown,status=200)=>({ok:status<400,status,text:async()=>JSON.stringify(body)}) as Response
function mount(failSave=false) {
 const fetch=vi.fn(async (_:unknown, init?:RequestInit)=>init?.method==='POST'
  ? failSave ? json({message:'Could not save issue'},503) : json({issueId:'i1',description:'Projector flickers',createdAt:'2027-03-01T01:00:00Z'})
  : json([]))
 vi.stubGlobal('fetch',fetch)
 render(<VenueOperationalIssuesPanel venueId="v1" venueAddress="Main Hall"/>)
 return fetch
}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks()})
describe('VS13 operational issue form',()=>{
 it('VS13 (negative): blank descriptions and incomplete or reversed periods never write',async()=>{
  const fetch=mount();await screen.findByText('No operational issues reported.')
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  expect(screen.getByRole('alert')).toHaveTextContent('Issue description is required.')
  await userEvent.type(screen.getByLabelText('Issue description'),'Projector flickers')
  fireEvent.change(screen.getByLabelText('Affected from (optional)'),{target:{value:'2027-03-10T10:30'}})
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  expect(screen.getByRole('alert')).toHaveTextContent('Enter both the affected start and end date/time.')
  fireEvent.change(screen.getByLabelText('Affected until (optional)'),{target:{value:'2027-03-10T09:30'}})
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  expect(screen.getByRole('alert')).toHaveTextContent('Affected end date/time must be after the start date/time.')
  expect(fetch.mock.calls.some(c=>c[1]?.method==='POST')).toBe(false)
 })
 it('VS13 (happy): sends Singapore wall-clock times as the correct instants and keeps the staff description',async()=>{
  const fetch=mount();await screen.findByText('No operational issues reported.')
  expect(screen.getByText('Affected times are in Singapore time.')).toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('Issue description'),'Projector flickers')
  fireEvent.change(screen.getByLabelText('Affected from (optional)'),{target:{value:'2027-03-10T10:30'}})
  fireEvent.change(screen.getByLabelText('Affected until (optional)'),{target:{value:'2027-03-10T12:30'}})
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  await screen.findByText('Operational issue reported successfully.')
  expect(JSON.parse(String(fetch.mock.calls.find(c=>c[1]?.method==='POST')?.[1]?.body))).toEqual({description:'Projector flickers',affectedFrom:'2027-03-10T02:30:00.000Z',affectedUntil:'2027-03-10T04:30:00.000Z'})
  expect(screen.getByText('Projector flickers')).toBeInTheDocument()
  expect(screen.getByLabelText('Issue description')).toHaveValue('')
 })
 it('VS13 (failure): save failures preserve entered details and allow a deliberate retry',async()=>{
  const fetch=mount(true);await screen.findByText('No operational issues reported.')
  await userEvent.type(screen.getByLabelText('Issue description'),'Projector flickers')
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save issue')
  expect(screen.getByLabelText('Issue description')).toHaveValue('Projector flickers')
  fetch.mockImplementation(async (_:unknown, init?:RequestInit)=>init?.method==='POST' ? json({issueId:'i1',description:'Projector flickers',createdAt:'2027-03-01T01:00:00Z'}) : json([]))
  await userEvent.click(screen.getByRole('button',{name:'Report issue'}))
  await waitFor(()=>expect(fetch.mock.calls.filter(c=>c[1]?.method==='POST')).toHaveLength(2))
  expect(await screen.findByText('Operational issue reported successfully.')).toBeInTheDocument()
 })
})

describe('DEV16 operational issue coverage', () => {
 it.each([false, true])('DEV16 (cross-cutting): leaving issue details ignores late read outcomes (%s)', async failed => {
  let resolve!: (value: unknown) => void, reject!: (cause: unknown) => void
  const read = vi.spyOn(apiClient, 'get').mockReturnValue(new Promise((done, fail) => { resolve = done; reject = fail }))
  mount()
  cleanup()
  if (failed) reject(new Error('Offline')); else resolve([])
  await Promise.resolve()
  expect(read).toHaveBeenCalledExactlyOnceWith('/venues/v1/operational-issues', { cache: 'no-store' })
  expect(screen.queryByRole('heading', { name: 'Operational issues' })).not.toBeInTheDocument()
 })
 it('DEV16 (happy): refresh replaces the list and displays the saved affected period in Singapore time', async () => {
  const read = vi.spyOn(apiClient, 'get').mockResolvedValue([])
  mount()
  await screen.findByText('No operational issues reported.')
  read.mockResolvedValue([{ issueId: 'timed', description: 'Projector unavailable', affectedFrom: '2027-03-10T02:30:00Z',
    affectedUntil: '2027-03-10T04:30:00Z', createdAt: '2027-03-01T01:00:00Z' }])
  fireEvent.click(screen.getByRole('button', { name: 'Refresh issues' }))
  expect(await screen.findByText('Projector unavailable')).toBeInTheDocument()
  expect(screen.getByText(/10:30 am/)).toHaveTextContent('12:30 pm')
  expect(screen.queryByText('No operational issues reported.')).not.toBeInTheDocument()
 })
 it('DEV16 (negative): an end without a start and descriptions over the limit do not write', async () => {
  const fetch = mount()
  await screen.findByText('No operational issues reported.')
  fireEvent.change(screen.getByLabelText('Issue description'), { target: { value: 'Faulty projector' } })
  fireEvent.change(screen.getByLabelText('Affected until (optional)'), { target: { value: '2027-03-10T12:30' } })
  fireEvent.click(screen.getByRole('button', { name: 'Report issue' }))
  expect(screen.getByRole('alert')).toHaveTextContent('Enter both')
  fireEvent.change(screen.getByLabelText('Affected until (optional)'), { target: { value: '' } })
  fireEvent.change(screen.getByLabelText('Issue description'), { target: { value: 'x'.repeat(2001) } })
  fireEvent.click(screen.getByRole('button', { name: 'Report issue' }))
  expect(screen.getByRole('alert')).toHaveTextContent('at most 2000 characters')
  expect(fetch.mock.calls.some(c => c[1]?.method === 'POST')).toBe(false)
 })
 it('DEV16 (failure): read and refresh failures offer recovery without inventing an empty list', async () => {
  const read = vi.spyOn(apiClient, 'get').mockRejectedValue(new Error('Offline'))
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load operational issues.')
  expect(screen.queryByText('No operational issues reported.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh issues' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load operational issues.')
  read.mockResolvedValue([])
  fireEvent.click(screen.getByRole('button', { name: 'Refresh issues' }))
  expect(await screen.findByText('No operational issues reported.')).toBeInTheDocument()
 })
 it('DEV16 (failure): a failed manual refresh displays the server message while retaining the last issue list', async () => {
  const fetch = mount()
  await screen.findByText('No operational issues reported.')
  fetch.mockImplementation(async () => json({ message: 'Issue history unavailable' }, 503))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh issues' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Issue history unavailable')
  expect(screen.getByText('No operational issues reported.')).toBeInTheDocument()
 })
 it('DEV16 (failure): unexpected save failures retain input and a successful retry before the initial read is safe', async () => {
  vi.spyOn(apiClient, 'get').mockReturnValue(new Promise(() => {}))
  const post = vi.spyOn(apiClient, 'post').mockRejectedValueOnce(new Error('Offline')).mockResolvedValue({
    issueId: 'new', description: 'Projector flickers', createdAt: '2027-03-01T01:00:00Z' })
  mount()
  fireEvent.change(screen.getByLabelText('Issue description'), { target: { value: 'Projector flickers' } })
  fireEvent.click(screen.getByRole('button', { name: 'Report issue' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not save the operational issue.')
  expect(screen.getByLabelText('Issue description')).toHaveValue('Projector flickers')
  fireEvent.click(screen.getByRole('button', { name: 'Report issue' }))
  expect(await screen.findByText('Operational issue reported successfully.')).toBeInTheDocument()
  expect(screen.getByText('Period not specified')).toBeInTheDocument()
  expect(post).toHaveBeenLastCalledWith('/venues/v1/operational-issues', { description: 'Projector flickers', affectedFrom: null, affectedUntil: null })
 })
})
