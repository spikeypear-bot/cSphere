// @stories VS17 VS07 EC08
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { BookingAssessment } from './BookingAssessment'
const json = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
const assessment = (failed = false) => ({ bookingId: 'b1', status: 'attention', checks: failed ? [{ key: 'capacity', status: 'attention', detail: '200 expected; capacity 150.' }] : [],
 history: [{ assessmentId: 'a1', outcome: 'attention', source: 'Venue details changed', reason: 'Capacity was reduced.', actorName: 'vs1', createdAt: '2027-03-01T01:00:00Z' }] })
function page(staff = true) { render(<MemoryRouter><BookingAssessment {...(staff ? { bookingId: 'b1' } : { eventId: 'e1' })} /></MemoryRouter>) }
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('VS17 booking suitability reassessment', () => {
 it('VS17 (happy): current venue changes appear on focus without navigation and block approval', async () => {
  let current = assessment()
  vi.stubGlobal('fetch', vi.fn(async () => json(current)))
  page(); expect(await screen.findByRole('button', { name: 'Restore approval' })).toBeEnabled()
  current = assessment(true); fireEvent.focus(window)
  expect(await screen.findByText('200 expected; capacity 150.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Restore approval' })).toBeDisabled()
 })
 it('VS17 (happy): staff restore approval with a comment and retain previous history', async () => {
  const current = assessment()
  const fetch = vi.fn(async (_: unknown, init?: RequestInit) => {
   if (init?.method === 'POST') { current.status = 'approved'; current.history.unshift({ ...current.history[0], assessmentId: 'a2', outcome: 'restored', reason: 'Capacity restored and checked.' }); return json(current) }
   return json(current)
  }); vi.stubGlobal('fetch', fetch); page()
  await userEvent.type(await screen.findByLabelText('Reassessment comment'), 'Capacity restored and checked.')
  await userEvent.click(screen.getByRole('button', { name: 'Restore approval' }))
  await screen.findByText('Approval restored', { selector: 'span' })
  const post = fetch.mock.calls.find(c => c[1]?.method === 'POST')
  expect(JSON.parse(String(post?.[1]?.body))).toEqual({ assessmentId: 'a1', note: 'Capacity restored and checked.' })
  expect(screen.getByText('Capacity was reduced.')).toBeInTheDocument()
 })
 it('VS17 (negative): coordinator sees issues and replacement action without staff controls', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([assessment(true)]))); page(false)
  expect(await screen.findByRole('link', { name: 'Find replacement venue' })).toHaveAttribute('href', '/coordinator/events/e1/venue-booking?replacesBookingId=b1')
  expect(screen.queryByRole('button', { name: 'Restore approval' })).not.toBeInTheDocument()
 })
 it('VS17 (failure): failed saves preserve the comment for retry', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_: unknown, init?: RequestInit) => init?.method === 'POST' ? json({ message: 'Save failed' }, 503) : json(assessment())))
  page(); await userEvent.type(await screen.findByLabelText('Reassessment comment'), 'Checked again')
  await userEvent.click(screen.getByRole('button', { name: 'Restore approval' }))
  expect(await screen.findByText('Save failed')).toBeInTheDocument()
  expect(screen.getByLabelText('Reassessment comment')).toHaveValue('Checked again')
 })
 it('VS17 (conflict): a live assessment change requires staff to restart rather than silently overwrite', async () => {
  let current = assessment(); vi.stubGlobal('fetch', vi.fn(async () => json(current)))
  page(); await userEvent.type(await screen.findByLabelText('Reassessment comment'), 'Old assessment')
  current = { ...assessment(), history: [{ ...current.history[0], assessmentId: 'a2' }] }; fireEvent.focus(window)
  expect(await screen.findByRole('button', { name: 'Restart reassessment' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Restore approval' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: 'Restart reassessment' }))
  expect(screen.getByLabelText('Reassessment comment')).toHaveValue('')
 })
 it('VS17 (failure): failed live reads keep the open form and comment, disable saving and permit retry', async () => {
  let fail = false; vi.stubGlobal('fetch', vi.fn(async () => fail ? json({ message: 'Offline' }, 503) : json(assessment())))
  page(); await userEvent.type(await screen.findByLabelText('Reassessment comment'), 'Unsaved comment')
  fail = true; fireEvent.focus(window); await screen.findByRole('button', { name: 'Retry assessments' })
  expect(screen.getByLabelText('Reassessment comment')).toHaveValue('Unsaved comment')
  expect(screen.getByRole('button', { name: 'Restore approval' })).toBeDisabled()
  fail = false; await userEvent.click(screen.getByRole('button', { name: 'Retry assessments' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Restore approval' })).toBeEnabled())
 })
})
