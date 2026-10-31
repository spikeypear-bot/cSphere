// @stories DEV16
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { VenueDto } from '../../types/venue'
import VenueMap from './VenueMap'

const map = vi.hoisted(() => ({ setView: vi.fn(), getZoom: () => 11,
  invalidateSize: vi.fn(), getContainer: () => document.body }))
const leaflet = vi.hoisted(() => ({ openPopup: vi.fn(), preventDefault: vi.fn(), tilesMounted: vi.fn() }))
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  TileLayer: ({ eventHandlers }: { eventHandlers: { tileerror: () => void } }) => {
    leaflet.tilesMounted()
    return <button onClick={eventHandlers.tileerror}>Simulate unavailable tiles</button>
  },
  Marker: ({ children, title, eventHandlers, ref }: { children: ReactNode; title: string;
    ref: { current: unknown }; eventHandlers: { click: () => void; keydown: (event: unknown) => void } }) => {
    ref.current = { openPopup: leaflet.openPopup }
    return <div><button title={title} onClick={eventHandlers.click}
      onKeyDown={event => eventHandlers.keydown({ originalEvent: { key: event.key, preventDefault: leaflet.preventDefault } })}>{title}</button>{children}</div>
  },
  Popup: ({ children }: { children: ReactNode }) => <div>{children}</div>, useMap: () => map,
}))
const venue: VenueDto = { venueId: 'room', venueAddress: 'Room', venueCapacity: 50,
  supportedLayouts: [], venueFacilities: [], venueAccessibilities: [], operatingInformation: '', additionalInformation: null }
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals() })
describe('DEV16 map viewport', () => {
  it('DEV16 (boundary): selecting missing, nonfinite or out-of-range coordinates never moves the map', () => {
    const page = render(<VenueMap venues={[venue]} selectedId="room" onSelect={() => {}} />)
    for (const latitude of [null, undefined, NaN, Infinity, 91]) {
      page.rerender(<VenueMap venues={[{ ...venue, latitude, longitude: 103.8 }]} selectedId="room" onSelect={() => {}} />)
    }
    expect(map.setView).not.toHaveBeenCalled()
    page.rerender(<VenueMap venues={[{ ...venue, latitude: 1.3, longitude: 103.8 }]} selectedId="room" onSelect={() => {}} />)
    expect(map.setView).toHaveBeenCalledExactlyOnceWith([1.3, 103.8], 15)
  })
  it('DEV16 (cross-cutting): responsive container changes preserve the centre and disconnect observation on leaving', () => {
    let resized: (() => void) | undefined
    const disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: () => void) { resized = callback }
      observe = vi.fn()
      disconnect = disconnect
    })
    const page = render(<VenueMap venues={[]} onSelect={() => {}} />)
    resized!()
    expect(map.invalidateSize).toHaveBeenCalledExactlyOnceWith({ animate: false, debounceMoveend: true })
    page.unmount()
    expect(disconnect).toHaveBeenCalledOnce()
  })
  it('DEV16 (happy): a numbered single venue supports marker selection, keyboard activation and reset', () => {
    const onSelect = vi.fn()
    render(<VenueMap venues={[{ ...venue, latitude: 1.3, longitude: 103.8 }]} onSelect={onSelect} />)
    expect(screen.getByText('1 venue on map')).toBeInTheDocument()
    const marker = screen.getByTitle('Room')
    fireEvent.click(marker)
    expect(onSelect).toHaveBeenLastCalledWith('room')
    fireEvent.keyDown(marker, { key: 'Escape' })
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(leaflet.preventDefault).not.toHaveBeenCalled()
    for (const key of ['Enter', ' ']) fireEvent.keyDown(marker, { key })
    expect(onSelect).toHaveBeenCalledTimes(3)
    expect(leaflet.preventDefault).toHaveBeenCalledTimes(2)
    expect(leaflet.openPopup).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: 'Reset map' }))
    expect(map.setView).toHaveBeenLastCalledWith([1.3521, 103.8198], 11)
  })
  it('DEV16 (happy): colocated rooms can be chosen individually and a group retains the selected room', () => {
    const onSelect = vi.fn()
    const rooms = [{ ...venue, latitude: 1.3, longitude: 103.8 },
      { ...venue, venueId: 'second', venueName: 'Second room', latitude: 1.3, longitude: 103.8 }]
    const page = render(<VenueMap venues={rooms} onSelect={onSelect} />)
    const marker = screen.getByTitle('2 venues near Room')
    expect(screen.getByText('Choose a venue at this location')).toBeInTheDocument()
    fireEvent.click(marker)
    expect(onSelect).toHaveBeenLastCalledWith('room')
    fireEvent.click(screen.getByRole('button', { name: 'Second room' }))
    expect(onSelect).toHaveBeenLastCalledWith('second')
    page.rerender(<VenueMap venues={rooms} selectedId="second" onSelect={onSelect} />)
    expect(screen.getByRole('button', { name: 'Second room' })).toHaveAttribute('aria-pressed', 'true')
    expect(leaflet.openPopup).toHaveBeenCalledOnce()
    fireEvent.click(marker)
    fireEvent.keyDown(marker, { key: 'Enter' })
    expect(onSelect.mock.calls.slice(-2)).toEqual([['second'], ['second']])
  })
  it('DEV16 (failure): tile failure preserves venue selection and retry creates a fresh tile layer', async () => {
    const onSelect = vi.fn()
    render(<VenueMap venues={[{ ...venue, latitude: 1.3, longitude: 103.8 }]} onSelect={onSelect} />)
    fireEvent.click(screen.getByRole('button', { name: 'Simulate unavailable tiles' }))
    expect(screen.getByRole('status')).toHaveTextContent('You can still browse and select venues.')
    fireEvent.click(screen.getByTitle('Room'))
    expect(onSelect).toHaveBeenCalledWith('room')
    const before = leaflet.tilesMounted.mock.calls.length
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Retry map tiles' })))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(leaflet.tilesMounted.mock.calls.length).toBeGreaterThan(before)
  })
})
