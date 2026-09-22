// Everything that knows how a logged-in session is stored in this browser.
// Kept separate from apiClient/session so there is exactly one place that
// understands the storage key and the claim-to-route-vocabulary mapping.

export type Role = 'organiser' | 'coordinator' | 'venue-staff' | 'technical-support' | 'attendee'

/** Mirrors the backend's LoginResponse (user/dto/LoginResponse.java). */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userId: string
  username: string
  role: string
  organisation: string | null
}

export interface AuthTokens {
  accessToken: string
  refreshToken: string
  userId: string
  username: string
  role: Role
  organisation: string | null
}

export const AUTH_STORAGE_KEY = 'connectsphere.auth'

// The backend's UserRole enum uses the abbreviations the database stores
// (`user_role` in SCHEMA.md); the frontend has always used long route
// segments. Translating here rather than renaming either side keeps the URL
// vocabulary readable and the database labels untouched.
const ROLE_BY_CLAIM: Record<string, Role> = {
  eo: 'organiser',
  ec: 'coordinator',
  vs: 'venue-staff',
  technician: 'technical-support',
  attendee: 'attendee',
}

export function roleFromClaim(claim: string | null | undefined): Role | null {
  return claim ? ROLE_BY_CLAIM[claim] ?? null : null
}

/** Null for a response whose role this build doesn't know — treated as not
 * logged in rather than dropping the user into a console chosen at random. */
export function tokensFromResponse(response: LoginResponse): AuthTokens | null {
  const role = roleFromClaim(response.role)
  if (!role || !response.accessToken) return null
  return {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    userId: response.userId,
    username: response.username,
    role,
    organisation: response.organisation ?? null,
  }
}

export function readTokens(): AuthTokens | null {
  try {
    const raw = window.localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<AuthTokens>
    if (!parsed.accessToken || !parsed.role) return null
    return {
      accessToken: parsed.accessToken,
      refreshToken: parsed.refreshToken ?? '',
      userId: parsed.userId ?? '',
      username: parsed.username ?? '',
      role: parsed.role,
      organisation: parsed.organisation ?? null,
    }
  } catch {
    // Private browsing, storage disabled, or a corrupted value — treat as
    // logged out rather than letting a storage quirk crash the app.
    return null
  }
}

export function writeTokens(tokens: AuthTokens): void {
  try {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(tokens))
  } catch {
    // The session still works for this page load, it just won't survive a
    // reload. Not worth failing a successful login over.
  }
}

export function clearTokens(): void {
  try {
    window.localStorage.removeItem(AUTH_STORAGE_KEY)
  } catch {
    // Nothing useful to do; the in-memory session is cleared regardless.
  }
}
