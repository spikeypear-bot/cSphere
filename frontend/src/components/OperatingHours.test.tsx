// @stories VS17 VS07 EO04 EC03
import { afterEach, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { OperatingHours } from './OperatingHours'
afterEach(cleanup)
it('VS17 (happy): orders days and displays whole and half hours with am/pm', () => {
 render(<OperatingHours hours={[
  { dayOfWeek: 2, openTime: '10:30:00', closeTime: '23:30:00' },
  { dayOfWeek: 1, openTime: '09:00:00', closeTime: '18:00:00' },
 ]} />)
 const rows = within(screen.getByRole('list', { name: 'Weekly operating hours' })).getAllByRole('listitem')
 expect(rows.map(r => r.textContent)).toEqual(['Monday9am – 6pm', 'Tuesday10:30am – 11:30pm'])
 expect(screen.getByText('Singapore time')).toBeInTheDocument()
})
it('VS17 (boundary): distinguishes midnight and noon and retains meaningful seconds', () => {
 render(<OperatingHours hours={[{ dayOfWeek: 7, openTime: '00:00:30', closeTime: '12:00:00' }]} />)
 expect(screen.getByText('12:00:30am – 12pm')).toBeInTheDocument()
})
it.each([undefined, [], [{dayOfWeek: 0, openTime: '09:00', closeTime: '18:00'}],
 [{dayOfWeek: 1, openTime: '25:00', closeTime: '26:00'}],
 [{dayOfWeek: 1, openTime: '18:00', closeTime: '09:00'}]])('VS17 (negative): does not advertise missing or invalid schedules: %j', hours => {
 render(<OperatingHours hours={hours} />)
 expect(screen.getByText('Operating hours not configured')).toBeInTheDocument()
 expect(screen.queryByRole('list')).not.toBeInTheDocument()
})
