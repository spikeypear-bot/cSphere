import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { UNAUTHORISED_EVENT } from './apiClient'
import {
  SessionContext,
  SESSION_STORAGE_KEY,
  readStoredSession,
  type Session,
  type SessionContextValue,
} from './sessionContext'

// Only SessionProvider (a component) is exported from this file —
// react-refresh/only-export-components requires that. Everything else
// (useSession, Role, Session) lives in ./sessionContext; import from there.

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>(readStoredSession)

  useEffect(() => {
    try {
      window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
    } catch {
      // Nothing useful to do if storage is unavailable — the session still
      // works for the current page load, it just won't survive a refresh.
    }
  }, [session])

  useEffect(() => {
    // Any 401/403 from apiClient means this "session" is no longer valid —
    // clear it so route guards (App.tsx's useRoleGate) bounce the user back
    // to the role selector, the same place an actual expired login would.
    function handleUnauthorised() {
      setSession({ role: null, organisation: null })
    }
    window.addEventListener(UNAUTHORISED_EVENT, handleUnauthorised)
    return () => window.removeEventListener(UNAUTHORISED_EVENT, handleUnauthorised)
  }, [])

  const value = useMemo<SessionContextValue>(
    () => ({
      ...session,
      loginAs: (role, organisation) => setSession({ role, organisation: organisation ?? null }),
      logout: () => setSession({ role: null, organisation: null }),
    }),
    [session],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
