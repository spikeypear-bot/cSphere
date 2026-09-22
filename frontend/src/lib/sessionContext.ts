import { createContext, useContext } from 'react'
import type { Role } from './authTokens'

// The signed-in account, as the UI needs to see it. Since D19/D20 every field
// here comes from the access token the backend issued — not from anything the
// person typed and not from a role selector. `organisation` in particular is
// display-and-storage-key only: the backend reads its own claim and ignores
// anything the client sends (see D20).
//
// Split from session.tsx (which needs JSX) purely so this file only exports
// non-component values — react-refresh/only-export-components requires a
// component-only file for Fast Refresh to work reliably.

export type { Role }

export interface Session {
  role: Role | null
  username: string | null
  organisation: string | null
}

export interface SessionContextValue extends Session {
  /** Rejects with an ApiClientError whose message is safe to show. */
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext)
  if (!context) {
    throw new Error('useSession must be used within a SessionProvider')
  }
  return context
}

/** Where each role lands after logging in, and what its route prefix is. */
export const HOME_BY_ROLE: Record<Role, string> = {
  organiser: '/organiser',
  coordinator: '/coordinator',
  'venue-staff': '/venue-staff',
  'technical-support': '/technical-support',
  attendee: '/attendee',
}
