// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppShell } from './AppShell'
import type { NavItem } from './navTypes'

// A made-up console: the components must work for any role's config.
const ITEMS: NavItem[] = [
  { label: 'Home', path: '/console' },
  { label: 'Orders', path: '/console/orders' },
  { label: 'Stock', path: '/console/stock' },
]

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppShell navLabel="Example console" items={ITEMS}>
        <Routes>
          <Route path="/console" element={<h1>Home page</h1>} />
          <Route path="/console/orders" element={<h1>Orders page</h1>} />
          <Route path="/console/orders/:id" element={<h1>One order</h1>} />
          <Route path="/console/stock" element={<h1>Stock page</h1>} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  )
}

describe('TS09 reusable side navigation', () => {
  afterEach(cleanup)

  it('TS09 (happy): renders every configured entry in a labelled nav and marks only the current page', () => {
    renderAt('/console/stock')

    const nav = screen.getByRole('navigation', { name: 'Example console' })
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Home', 'Orders', 'Stock'])
    expect(within(nav).getByRole('link', { name: 'Stock' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current')
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/console/orders')
  })

  it('TS09 (boundary): the home entry is current on its exact path only, a section stays current on its sub-pages', () => {
    const first = renderAt('/console')
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Orders' })).not.toHaveAttribute('aria-current')
    first.unmount()

    renderAt('/console/orders/42')
    expect(screen.getByRole('heading', { name: 'One order' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Orders' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current')
  })

  it('TS09 (cross-cutting): on a narrow screen the Menu toggle opens the list and choosing a page closes it', async () => {
    const user = userEvent.setup()
    renderAt('/console')

    const toggle = screen.getByRole('button', { name: 'Menu' })
    const list = screen.getByRole('list')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', list.id)
    expect(list).not.toHaveClass('side-nav__list--open')

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(list).toHaveClass('side-nav__list--open')

    await user.click(screen.getByRole('link', { name: 'Orders' }))
    expect(screen.getByRole('heading', { name: 'Orders page' })).toBeInTheDocument()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(list).not.toHaveClass('side-nav__list--open')
  })
})
