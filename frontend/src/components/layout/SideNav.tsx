import { useEffect, useRef } from 'react'
import { NavLink } from 'react-router-dom'
import { NavIcon } from './NavIcon'
import type { NavItem } from './navTypes'
import './layout.css'

/** True when another entry lives below this one, as a console's home does.
 * Such an entry matches its exact path only, so "Dashboard" is not also
 * highlighted on every other page of the console. */
function matchesExactly(item: NavItem, items: NavItem[]) {
  return items.some((other) => other !== item && other.path.startsWith(`${item.path}/`))
}

const classes = (...names: (string | false)[]) => names.filter(Boolean).join(' ')

interface SideNavProps {
  /** The nav's id, which the console's Menu button controls. */
  id: string
  label: string
  items: NavItem[]
  /** Desktop: shown as an icon rail rather than icons and labels. */
  collapsed: boolean
  onToggleCollapsed: () => void
  /** Narrow screens: the nav is an off-canvas drawer, open or closed. */
  drawerOpen: boolean
  onCloseDrawer: () => void
}

/** A role console's side navigation, driven entirely by `items`. The current
 * page's link carries aria-current="page" (NavLink sets it).
 *
 * On a wide screen it can collapse to an icon rail; each link then keeps its
 * label for assistive technology (visually hidden) and shows it as a tooltip.
 * On a narrow screen it is a drawer: opening it moves focus to the current
 * page's link, and Esc, the backdrop (console AppShell) or choosing a page
 * closes it. */
export function SideNav({ id, label, items, collapsed, onToggleCollapsed, drawerOpen, onCloseDrawer }: SideNavProps) {
  const listRef = useRef<HTMLUListElement>(null)

  useEffect(() => {
    if (!drawerOpen) return
    const links = listRef.current?.querySelectorAll<HTMLAnchorElement>('a')
    const target = listRef.current?.querySelector<HTMLAnchorElement>('a[aria-current="page"]') ?? links?.[0]
    target?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseDrawer()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen, onCloseDrawer])

  const toggleName = collapsed ? 'Expand navigation' : 'Collapse navigation'
  return (
    <nav
      id={id}
      className={classes('side-nav', collapsed && 'side-nav--collapsed', drawerOpen && 'side-nav--open')}
      aria-label={label}
    >
      <div className="side-nav__head">
        <span className="side-nav__title">{label}</span>
        <button
          type="button"
          className="side-nav__button side-nav__collapse"
          aria-expanded={!collapsed}
          aria-controls={id}
          aria-label={toggleName}
          title={toggleName}
          onClick={onToggleCollapsed}
        >
          <span aria-hidden="true">{collapsed ? '»' : '«'}</span>
        </button>
        <button type="button" className="side-nav__button side-nav__close" aria-label="Close menu" onClick={onCloseDrawer}>
          <span aria-hidden="true">×</span>
        </button>
      </div>
      <ul ref={listRef} className="side-nav__list">
        {items.map((item) => (
          <li key={item.path}>
            <NavLink
              to={item.path}
              end={matchesExactly(item, items)}
              className="side-nav__link"
              title={collapsed ? item.label : undefined}
              onClick={drawerOpen ? onCloseDrawer : undefined}
            >
              {item.icon
                ? <NavIcon name={item.icon} />
                : <span className="side-nav__letter" data-letter={item.label.charAt(0)} aria-hidden="true" />}
              <span className="side-nav__label">{item.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
