// @stories SO-C1
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { RowLink } from './RowLink'

afterEach(cleanup)

describe('SO-C1 a row that opens a page', () => {
  it('SO-C1 (boundary): a row with only a title is named by it and has nothing else to describe it', () => {
    render(<MemoryRouter><RowLink to="/safety-officer/events/e1" title="Town Hall" /></MemoryRouter>)
    const link = screen.getByRole('link', { name: 'Town Hall' })
    expect(link).toHaveAttribute('href', '/safety-officer/events/e1')
    expect(link).toHaveAttribute('class', 'row-link')
    expect(link).not.toHaveAttribute('aria-describedby')
  })
})
