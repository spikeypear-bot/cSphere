import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { login as apiLogin, logout as apiLogout, UNAUTHORISED_EVENT } from './apiClient'
import { readTokens } from './authTokens'
import { SessionContext, type Session, type SessionContextValue } from './sessionContext'

// Only SessionProvider (a component) is exported from this file —
// react-refresh/only-export-components requires that. Everything else
// (useSession, Role, Session) lives in ./sessionContext; import from there.

const LOGGED_OUT: Session = { role: null, username: null, organisation: null }

function currentSession(): Session {
  const tokens = readTokens()
  if (!tokens) return LOGGED_OUT
  return { role: tokens.role, username: tokens.username, organisation: tokens.organisation }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>(currentSession)

  useEffect(() => {
    // apiClient reports this only when the session is over and could not be
    // renewed — never for a 403, which means the session is fine and this
    // account simply isn't allowed to do that. Route guards then bounce the
    // user to the login page.
    function handleUnauthorised() {
      setSession(LOGGED_OUT)
    }
    window.addEventListener(UNAUTHORISED_EVENT, handleUnauthorised)
    return () => window.removeEventListener(UNAUTHORISED_EVENT, handleUnauthorised)
  }, [])

  const value = useMemo<SessionContextValue>(
    () => ({
      ...session,
      login: async (username, password) => {
        // apiClient writes the tokens; this mirrors them into React state so
        // the tree re-renders. Storage stays the source of truth, so a reload
        // resumes the same session.
        const tokens = await apiLogin(username, password)
        setSession({
          role: tokens.role,
          username: tokens.username,
          organisation: tokens.organisation,
        })
      },
      logout: async () => {
        setSession(LOGGED_OUT)
        await apiLogout()
      },
    }),
    [session],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
