import type { NavIconName } from './NavIcon'

/** One entry in a role console's side navigation. `path` is absolute
 * (e.g. "/technical-support/inventory"). An entry is highlighted on its own
 * page and every page below it, except an entry that has others below it
 * (the console's home), which is highlighted on its exact path only.
 * `icon` is what the entry shows when the navigation is collapsed to a rail;
 * an entry without one shows its first letter there. */
export interface NavItem {
  label: string
  path: string
  icon?: NavIconName
}
