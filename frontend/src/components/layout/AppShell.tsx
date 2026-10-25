import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { SideNav } from './SideNav'
import type { NavItem } from './navTypes'
import './layout.css'

/** Where the collapsed/expanded choice is remembered. Storage can be
 * unavailable (private windows, blocked site data), so every read and write
 * is guarded and the nav simply starts expanded. */
const COLLAPSED_KEY = 'connectsphere.sideNav.collapsed'
/** Matches layout.css: above this the nav is docked, below it a drawer. */
const WIDE = '(min-width: 901px)'

function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSED_KEY) === 'true'
  } catch {
    return false
  }
}

function writeCollapsed(collapsed: boolean) {
  try {
    window.localStorage.setItem(COLLAPSED_KEY, String(collapsed))
  } catch {
    // Not remembered this time; the nav still collapses.
  }
}

/** The layout of a role console: side navigation docked to the left edge
 * below the app-wide header (components/AppShell, which it does not
 * replace), and the page beside it. A role adopts it by passing its own nav
 * config; nothing here is specific to any role. */
export function AppShell({ navLabel, items, children }: {
  navLabel: string
  items: NavItem[]
  children: ReactNode
}) {
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const navId = useId()
  const menuRef = useRef<HTMLButtonElement>(null)
  const wasOpen = useRef(false)

  const toggleCollapsed = () => {
    writeCollapsed(!collapsed)
    setCollapsed(!collapsed)
  }
  const closeDrawer = useCallback(() => setDrawerOpen(false), [])

  // Focus goes back to the Menu button once the drawer has closed (and the
  // page is no longer inert).
  useEffect(() => {
    if (wasOpen.current && !drawerOpen) menuRef.current?.focus()
    wasOpen.current = drawerOpen
  }, [drawerOpen])

  // Widening the window past the drawer breakpoint closes the drawer, so the
  // page is never left inert behind a drawer that is no longer shown.
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const wide = window.matchMedia(WIDE)
    const onChange = () => { if (wide.matches) setDrawerOpen(false) }
    wide.addEventListener('change', onChange)
    return () => wide.removeEventListener('change', onChange)
  }, [])

  return (
    <div className={collapsed ? 'console-shell console-shell--collapsed' : 'console-shell'}>
      <div className="console-shell__rail">
        <SideNav
          id={navId}
          label={navLabel}
          items={items}
          collapsed={collapsed}
          onToggleCollapsed={toggleCollapsed}
          drawerOpen={drawerOpen}
          onCloseDrawer={closeDrawer}
        />
      </div>
      {drawerOpen ? <div className="console-shell__backdrop" data-testid="side-nav-backdrop" onClick={closeDrawer} /> : null}
      <div className="console-shell__page" inert={drawerOpen}>
        <div className="console-shell__bar">
          <button
            ref={menuRef}
            type="button"
            className="console-shell__menu"
            aria-expanded={drawerOpen}
            aria-controls={navId}
            onClick={() => setDrawerOpen(true)}
          >
            <span aria-hidden="true">☰</span>
            Menu
          </button>
        </div>
        <div className="console-shell__content">{children}</div>
      </div>
    </div>
  )
}
