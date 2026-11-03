// @stories EO-C2
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { DateTimeField } from './fields'

afterEach(cleanup)

describe('EO-C2 date and time fields read and write Singapore time', () => {
  it('EO-C2 (boundary): the earliest and latest allowed times are shown in Singapore time', () => {
    render(<DateTimeField id="opens" label="Registration opens" value={null} onChange={vi.fn()}
      min="2027-03-01T00:00:00Z" max="2027-03-10T01:00:00Z" />)
    const input = screen.getByLabelText(/Registration opens/)
    expect(input).toHaveAttribute('min', '2027-03-01T08:00')
    expect(input).toHaveAttribute('max', '2027-03-10T09:00')
  })

  it('EO-C2 (negative): clearing the field leaves no time, and a stored value that is not a time shows as empty', () => {
    const onChange = vi.fn()
    const view = render(<DateTimeField id="opens" label="Registration opens" value="2027-03-10T01:00:00Z" onChange={onChange} />)
    const input = screen.getByLabelText(/Registration opens/)
    expect(input).toHaveValue('2027-03-10T09:00')
    fireEvent.change(input, { target: { value: '' } })
    expect(onChange).toHaveBeenLastCalledWith(null)

    view.rerender(<DateTimeField id="opens" label="Registration opens" value="not a time" onChange={onChange} />)
    expect(input).toHaveValue('')
  })
})
