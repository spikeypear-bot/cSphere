/** The side navigation's small icon set: 20px line icons drawn in the
 * current text colour. Decorative only — every link also carries its label. */
const SHAPES = {
  dashboard: (
    <>
      <rect x="3" y="3" width="6" height="8" rx="1.5" />
      <rect x="11" y="3" width="6" height="5" rx="1.5" />
      <rect x="3" y="13" width="6" height="4" rx="1.5" />
      <rect x="11" y="10" width="6" height="7" rx="1.5" />
    </>
  ),
  requests: (
    <>
      <rect x="4" y="3" width="12" height="14.5" rx="2" />
      <path d="M7.5 3V2h5v1" />
      <path d="M7 8h6M7 11h6M7 14h3.5" />
    </>
  ),
  faulty: (
    <>
      <path d="M10 2.8 18 16.8H2z" strokeLinejoin="round" />
      <path d="M10 8v4" />
      <circle cx="10" cy="14.4" r="0.5" fill="currentColor" />
    </>
  ),
  inventory: (
    <>
      <path d="M10 2.5 17 6v8l-7 3.5L3 14V6z" strokeLinejoin="round" />
      <path d="M3 6l7 3.5L17 6M10 9.5v8" strokeLinejoin="round" />
    </>
  ),
  calendar: (
    <>
      <rect x="3" y="4" width="14" height="13" rx="2" />
      <path d="M3 8h14M7 2.5v3M13 2.5v3" />
    </>
  ),
  search: (
    <>
      <circle cx="8.5" cy="8.5" r="5" />
      <path d="m12.2 12.2 4.8 4.8" />
    </>
  ),
  changes: (
    <>
      <path d="M3 7h12M12 4l3 3-3 3" strokeLinejoin="round" />
      <path d="M17 13H5M8 10l-3 3 3 3" strokeLinejoin="round" />
    </>
  ),
} as const

export type NavIconName = keyof typeof SHAPES

export function NavIcon({ name }: { name: NavIconName }) {
  return (
    <svg className="nav-icon" viewBox="0 0 20 20" width="20" height="20" fill="none"
      stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true" focusable="false">
      {SHAPES[name]}
    </svg>
  )
}
