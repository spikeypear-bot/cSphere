// @stories EC03 EC04  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VenueDto } from '../../types/venue'
import type { EventVenueBookingDto } from '../../types/venueBookingRequest'
import { BookingVenueDetails } from './BookingVenueDetails'
import { VenueDetails } from './VenueDetails'

const venue = (over: Partial<VenueDto> = {}): VenueDto => ({
  venueId: 'v1', venueAddress: 'Marina Bay Hall', venueCapacity: 400, supportedLayouts: ['theatre'],
  venueFacilities: ['projection'], venueAccessibilities: ['step_free_access'], additionalInformation: null,
  operatingInformation: '', operatingHours: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }], ...over,
} as VenueDto)

const fact = (term: string) => screen.getByText(term, { selector: 'dt' }).nextElementSibling

describe('EC04 venue details (the shared details window body)', () => {
  afterEach(cleanup)

  it('EC04 (happy): shows capacity, layouts, facilities, accessibility, opening hours and any extra information', () => {
    render(<VenueDetails venue={venue({ additionalInformation: 'Loading bay at the back.' })} />)

    expect(fact('Capacity')).toHaveTextContent('400 people')
    expect(fact('Supported layouts')).toHaveTextContent('Theatre')
    expect(fact('Facilities')).toHaveTextContent('Projection')
    expect(fact('Accessibility')).toHaveTextContent('Step-free access')
    expect(within(fact('Operating hours') as HTMLElement).getByText('Monday')).toBeInTheDocument()
    expect(fact('Additional information')).toHaveTextContent('Loading bay at the back.')
  })

  it('EC04 (boundary): says what is not recorded instead of leaving gaps', () => {
    render(<VenueDetails venue={venue({ venueCapacity: null, supportedLayouts: [], venueFacilities: [],
      venueAccessibilities: [], additionalInformation: '   ' })} />)

    expect(fact('Capacity')).toHaveTextContent('Not recorded')
    expect(fact('Supported layouts')).toHaveTextContent('None recorded')
    expect(fact('Facilities')).toHaveTextContent('None recorded')
    expect(fact('Accessibility')).toHaveTextContent('None recorded')
    expect(screen.queryByText('Additional information')).not.toBeInTheDocument()
  })

  it('EC04 (boundary): a value with no display label is shown as stored, not dropped', () => {
    render(<VenueDetails venue={venue({ supportedLayouts: ['standing_room'], venueFacilities: ['rooftop'],
      venueAccessibilities: ['hearing_loop'] } as unknown as Partial<VenueDto>)} />)

    expect(fact('Supported layouts')).toHaveTextContent('standing_room')
    expect(fact('Facilities')).toHaveTextContent('rooftop')
    expect(fact('Accessibility')).toHaveTextContent('hearing_loop')
  })
})

describe('EC03 a booking\'s venue details window', () => {
  afterEach(cleanup)

  const booking = (over: Partial<EventVenueBookingDto> = {}) => ({
    bookingId: 'b1', status: 'approved', venueId: 'v1', venueAddress: 'Marina Bay Hall', venueCapacity: 400,
    operatingHours: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }], bookingNotes: null, suitabilityNote: null,
    rejectReason: null, submittedAt: null, submittedBy: null, ...over,
  } as EventVenueBookingDto)

  it('EC03 (happy): opens from its button with capacity and opening hours, and closes again', async () => {
    render(<BookingVenueDetails booking={booking()} />)

    await userEvent.click(screen.getByRole('button', { name: 'View venue details' }))
    const details = screen.getByRole('dialog', { name: 'Marina Bay Hall' })
    expect(within(details).getByText('400 people')).toBeInTheDocument()
    expect(within(details).getByText('Monday')).toBeInTheDocument()

    await userEvent.click(within(details).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('EC03 (boundary): a booking without a venue address or capacity still opens, saying so', async () => {
    render(<BookingVenueDetails booking={booking({ venueAddress: null, venueCapacity: null })} />)

    await userEvent.click(screen.getByRole('button', { name: 'View venue details' }))
    const details = screen.getByRole('dialog', { name: 'Venue' })
    expect(within(details).getByText('Not recorded')).toBeInTheDocument()
  })
})
