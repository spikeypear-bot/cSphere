// @stories ECL-C1 SO-C1
import { afterEach, describe, expect, it } from 'vitest'
import { AUTH_STORAGE_KEY, readTokens, roleFromClaim, tokensFromResponse, type LoginResponse } from './authTokens'
import { HOME_BY_ROLE } from './sessionContext'

function loginResponse(role: string): LoginResponse {
  return {
    accessToken: 'access-1', refreshToken: 'refresh-1', tokenType: 'Bearer', expiresIn: 900,
    userId: 'user-1', username: 'ecl1', role, organisation: 'ConnectSphere',
  }
}

describe('authTokens — role claim to console (ECL-C1)', () => {
  it("ECL-C1 (happy): the backend's `ecl` claim opens the Event Coordinator Lead console", () => {
    expect(roleFromClaim('ecl')).toBe('coordinator-lead')
    expect(HOME_BY_ROLE['coordinator-lead']).toBe('/coordinator-lead')
  })

  it('ECL-C1 (boundary): a Lead stays distinct from an Event Coordinator', () => {
    // `ec` and `ecl` differ by one letter.
    expect(roleFromClaim('ec')).toBe('coordinator')
    expect(roleFromClaim('ecl')).not.toBe(roleFromClaim('ec'))
  })

  it('ECL-C1 (happy): a Lead is signed in from a login response carrying the `ecl` role', () => {
    expect(tokensFromResponse(loginResponse('ecl'))).toMatchObject({
      username: 'ecl1', role: 'coordinator-lead', organisation: 'ConnectSphere',
    })
  })

  it('ECL-C1 (negative): a role this build does not know is treated as not signed in', () => {
    expect(tokensFromResponse(loginResponse('lead'))).toBeNull()
  })

  it('SO-C1 (happy): the Safety Officer role maps to its protected console', () => {
    expect(roleFromClaim('safety_officer')).toBe('safety-officer')
    expect(HOME_BY_ROLE['safety-officer']).toBe('/safety-officer')
    expect(tokensFromResponse(loginResponse('safety_officer'))?.role).toBe('safety-officer')
  })
})

describe('ECL-C1 authTokens — reading what this browser stored', () => {
  afterEach(() => window.localStorage.clear())

  it('ECL-C1 (negative): no role claim at all is not a role', () => {
    expect(roleFromClaim(null)).toBeNull()
    expect(roleFromClaim(undefined)).toBeNull()
    expect(roleFromClaim('')).toBeNull()
  })

  it('ECL-C1 (boundary): a login response with no organisation signs in with none', () => {
    expect(tokensFromResponse({ ...loginResponse('ec'), organisation: undefined as unknown as null })?.organisation).toBeNull()
  })

  it('ECL-C1 (boundary): a stored session missing its optional parts still reads, with blanks', () => {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'a', role: 'coordinator-lead' }))
    expect(readTokens()).toEqual({ accessToken: 'a', refreshToken: '', userId: '', username: '', role: 'coordinator-lead', organisation: null })
  })

  it('ECL-C1 (failure): a corrupted stored session is treated as signed out', () => {
    window.localStorage.setItem(AUTH_STORAGE_KEY, '{not json')
    expect(readTokens()).toBeNull()
  })
})
