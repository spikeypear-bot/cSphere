// @stories VS13 VS17
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { VenueOperationalIssuesPanel } from './VenueOperationalIssuesPanel'
const json=(body:unknown,status=200)=>({ok:status<400,status,text:async()=>JSON.stringify(body)}) as Response
function mount(failSave=false) {
 const fetch=vi.fn(async (_:unknown, init?:RequestInit)=>init?.method==='POST'
  ? failSave ? json({message:'Could not save issue'},503) : json({issueId:'i1',description:'Projector flickers',createdAt:'2027-03-01T01:00:00Z'})
  : json([]))
 vi.stubGlobal('fetch',fetch)
 render(<VenueOperationalIssuesPanel venueId="v1" venueAddress="Main Hall"/>)
 return fetch
}
afterEach(()=>{cleanup();vi.unstubAllGlobals()})
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
