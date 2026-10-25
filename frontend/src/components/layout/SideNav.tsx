import { useId, useState } from 'react'
import { NavLink } from 'react-router-dom'
import type { NavItem } from './navTypes'
import './layout.css'

/** True when another entry lives below this one, as a console's home does.
 * Such an entry matches its exact path only, so "Dashboard" is not also
 * highlighted on every other page of the console. */
function matchesExactly(item: NavItem, items: NavItem[]) {
  return items.some((other) => other !== item && other.path.startsWith(`${item.path}/`))
}

/** A role console's side navigation, driven entirely by `items`. The current
 * page's link carries aria-current="page" (NavLink sets it). On a narrow
 * screen the list collapses behind a Menu toggle; choosing a page closes it. */
export function SideNav({ label, items }: { label: string; items: NavItem[] }) {
  const [open, setOpen] = useState(false)
  const listId = useId()
  return (
    <nav className="side-nav" aria-label={label}>
      <button
        type="button"
        className="side-nav__toggle"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((value) => !value)}
      >
        <span aria-hidden="true">☰</span>
        Menu
      </button>
      <ul id={listId} className={open ? 'side-nav__list side-nav__list--open' : 'side-nav__list'}>
        {items.map((item) => (
          <li key={item.path}>
            <NavLink
              to={item.path}
              end={matchesExactly(item, items)}
              className="side-nav__link"
              onClick={() => setOpen(false)}
            >
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
