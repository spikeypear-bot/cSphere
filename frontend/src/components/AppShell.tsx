import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Card } from './ui/Card'
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
  const location = useLocation()

  // AU06. Originally rendered by the role selector; it lives here now because
  // the selector is gone and a signed-in user is redirected to their own
  // console rather than to a shared landing page. Signed-out visitors get the
  // equivalent notice from LoginPage, so this only fires when someone is
  // signed in and asked for a console that is not theirs.
  const deniedPath = role ? new URLSearchParams(location.search).get('access-denied') : null

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
      <main className="app-shell__content">
        {deniedPath ? (
          <Card className="app-shell__access-denied" role="alert">
            <h2>Access denied</h2>
            <p>
              You do not have permission to access <code>{deniedPath}</code> with your
              current role.
            </p>
          </Card>
        ) : null}
        {children}
      </main>
    </div>
  )
}
