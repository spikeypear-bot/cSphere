import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '../lib/sessionContext'
import './AppShell.css'

const ROLE_LABELS: Record<string, string> = {
  organiser: 'Event Organiser',
  coordinator: 'Event Coordinator',
  'venue-staff': 'Venue Staff',
  'technical-support': 'Technical Support Staff',
  attendee: 'Attendee',
}

export function AppShell({ children }: { children: ReactNode }) {
  const { role, username, organisation, logout } = useSession()

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <Link to="/" className="app-shell__brand">
          ConnectSphere
        </Link>
        {role ? (
          <div className="app-shell__session">
            <span>
              {username} · {ROLE_LABELS[role]}
              {organisation ? ` · ${organisation}` : ''}
            </span>
            <button type="button" className="app-shell__switch" onClick={() => void logout()}>
              Log out
            </button>
          </div>
        ) : null}
      </header>
      <main className="app-shell__content">{children}</main>
    </div>
  )
}
