// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
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
  afterEach(() => {
    cleanup()
    window.localStorage.clear()
  })

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

  it('TS09 (cross-cutting): on a narrow screen the Menu toggle opens the drawer and choosing a page closes it', async () => {
    const user = userEvent.setup()
    renderAt('/console')

    const toggle = screen.getByRole('button', { name: 'Menu' })
    const nav = screen.getByRole('navigation', { name: 'Example console' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', nav.id)
    expect(nav).not.toHaveClass('side-nav--open')

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(nav).toHaveClass('side-nav--open')

    await user.click(within(nav).getByRole('link', { name: 'Orders' }))
    expect(screen.getByRole('heading', { name: 'Orders page' })).toBeInTheDocument()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(nav).not.toHaveClass('side-nav--open')
    expect(toggle).toHaveFocus()
  })

  it('TS09 (happy): opening the drawer moves focus to the current page, and Esc closes it and returns focus to Menu', async () => {
    const user = userEvent.setup()
    renderAt('/console/stock')

    const toggle = screen.getByRole('button', { name: 'Menu' })
    await user.click(toggle)
    const nav = screen.getByRole('navigation', { name: 'Example console' })
    expect(within(nav).getByRole('link', { name: 'Stock' })).toHaveFocus()
    expect(screen.getByTestId('side-nav-backdrop')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(nav).not.toHaveClass('side-nav--open')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByTestId('side-nav-backdrop')).not.toBeInTheDocument()
    expect(toggle).toHaveFocus()
  })

  it('TS09 (happy): clicking the backdrop or Close menu closes the drawer without changing page', async () => {
    const user = userEvent.setup()
    renderAt('/console/orders')
    const toggle = screen.getByRole('button', { name: 'Menu' })
    const nav = screen.getByRole('navigation', { name: 'Example console' })

    await user.click(toggle)
    await user.click(screen.getByTestId('side-nav-backdrop'))
    expect(nav).not.toHaveClass('side-nav--open')
    expect(toggle).toHaveFocus()

    await user.click(toggle)
    await user.click(within(nav).getByRole('button', { name: 'Close menu' }))
    expect(nav).not.toHaveClass('side-nav--open')
    expect(toggle).toHaveFocus()
    expect(screen.getByRole('heading', { name: 'Orders page' })).toBeInTheDocument()
  })

  it('TS09 (boundary): Esc does nothing while the drawer is closed', async () => {
    const user = userEvent.setup()
    renderAt('/console')
    const toggle = screen.getByRole('button', { name: 'Menu' })

    await user.keyboard('{Escape}')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).not.toHaveFocus()
  })

  it('TS09 (happy): the collapse toggle turns the nav into an icon rail and back, every link keeping its name and a tooltip', async () => {
    const user = userEvent.setup()
    renderAt('/console/orders')
    const nav = screen.getByRole('navigation', { name: 'Example console' })
    const collapse = within(nav).getByRole('button', { name: 'Collapse navigation' })
    expect(collapse).toHaveAttribute('aria-expanded', 'true')
    expect(within(nav).getByRole('link', { name: 'Orders' })).not.toHaveAttribute('title')

    await user.click(collapse)
    const expand = within(nav).getByRole('button', { name: 'Expand navigation' })
    expect(expand).toHaveAttribute('aria-expanded', 'false')
    expect(nav).toHaveClass('side-nav--collapsed')
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('title'))).toEqual(['Home', 'Orders', 'Stock'])
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('aria-current', 'page')
    expect(window.localStorage.getItem('connectsphere.sideNav.collapsed')).toBe('true')

    await user.click(expand)
    expect(nav).not.toHaveClass('side-nav--collapsed')
    expect(within(nav).getByRole('button', { name: 'Collapse navigation' })).toHaveAttribute('aria-expanded', 'true')
    expect(window.localStorage.getItem('connectsphere.sideNav.collapsed')).toBe('false')
  })

  it('TS09 (happy): a collapsed rail is remembered on the next visit', () => {
    window.localStorage.setItem('connectsphere.sideNav.collapsed', 'true')
    renderAt('/console')

    const nav = screen.getByRole('navigation', { name: 'Example console' })
    expect(nav).toHaveClass('side-nav--collapsed')
    expect(within(nav).getByRole('button', { name: 'Expand navigation' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('TS09 (failure): when storage throws the nav starts expanded and still collapses', async () => {
    const user = userEvent.setup()
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    try {
      renderAt('/console')
      const nav = screen.getByRole('navigation', { name: 'Example console' })
      expect(nav).not.toHaveClass('side-nav--collapsed')

      await user.click(within(nav).getByRole('button', { name: 'Collapse navigation' }))
      expect(nav).toHaveClass('side-nav--collapsed')
      expect(setItem).toHaveBeenCalled()
      expect(getItem).toHaveBeenCalled()
    } finally {
      getItem.mockRestore()
      setItem.mockRestore()
    }
  })

  it('TS09 (boundary): an entry without an icon still has its label as its name in the rail', async () => {
    const user = userEvent.setup()
    renderAt('/console')
    const nav = screen.getByRole('navigation', { name: 'Example console' })
    await user.click(within(nav).getByRole('button', { name: 'Collapse navigation' }))

    const home = within(nav).getByRole('link', { name: 'Home' })
    expect(home.querySelector('.side-nav__letter')).toHaveAttribute('data-letter', 'H')
    expect(home).toHaveTextContent(/^Home$/)
  })
})
