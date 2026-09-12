import { createContext, useContext } from 'react'

// Interim access model — see docs/decision-log.md D6a. There is no real login
// yet: a role is chosen on the main page, and (for Event Organiser) an
// organisation name is captured so the backend has something to scope by via
// the X-Organisation header (see apiClient.ts / EventRequestController).
// Real accounts will replace this; keep this module as the single place that
// knows about the substitution, so swapping it out later touches one file.
//
// Split from SessionProvider.tsx (which needs JSX) purely so this file only
// exports non-component values — react-refresh/only-export-components
// requires a component-only file for Fast Refresh to work reliably.

export type Role = 'organiser' | 'coordinator' | 'venue-staff' | 'technical-support' | 'attendee'

export interface Session {
  role: Role | null
  organisation: string | null
}

export interface SessionContextValue extends Session {
  loginAs: (role: Role, organisation?: string) => void
  logout: () => void
}

export const SESSION_STORAGE_KEY = 'connectsphere.session'

export function readStoredSession(): Session {
  try {
    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY)
    if (!raw) return { role: null, organisation: null }
    const parsed = JSON.parse(raw) as Partial<Session>
    return { role: parsed.role ?? null, organisation: parsed.organisation ?? null }
  } catch {
    // Private browsing, storage disabled, or corrupted value — fall back to
    // "logged out" rather than letting a storage quirk crash the app.
    return { role: null, organisation: null }
  }
}

export const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext)
  if (!context) {
    throw new Error('useSession must be used within a SessionProvider')
  }
  return context
}
