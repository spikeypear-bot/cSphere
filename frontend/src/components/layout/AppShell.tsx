import type { ReactNode } from 'react'
import { SideNav } from './SideNav'
import type { NavItem } from './navTypes'
import './layout.css'

/** The layout of a role console: side navigation beside the page. It sits
 * inside the app-wide header (components/AppShell), which it does not
 * replace. A role adopts it by passing its own nav config; nothing here is
 * specific to any role. */
export function AppShell({ navLabel, items, children }: {
  navLabel: string
  items: NavItem[]
  children: ReactNode
}) {
  return (
    <div className="console-shell">
      <SideNav label={navLabel} items={items} />
      <div className="console-shell__page">{children}</div>
    </div>
  )
}
