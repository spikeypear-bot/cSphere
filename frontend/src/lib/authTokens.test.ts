import { describe, expect, it } from 'vitest'
import { roleFromClaim, tokensFromResponse, type LoginResponse } from './authTokens'
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

  it('maps the Safety Officer role to its protected console', () => {
    expect(roleFromClaim('safety_officer')).toBe('safety-officer')
    expect(HOME_BY_ROLE['safety-officer']).toBe('/safety-officer')
    expect(tokensFromResponse(loginResponse('safety_officer'))?.role).toBe('safety-officer')
  })
})
