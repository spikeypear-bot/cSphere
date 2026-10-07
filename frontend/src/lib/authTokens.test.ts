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
  it("maps the backend's `ecl` claim to the Event Coordinator Lead console", () => {
    expect(roleFromClaim('ecl')).toBe('coordinator-lead')
    expect(HOME_BY_ROLE['coordinator-lead']).toBe('/coordinator-lead')
  })

  it('keeps the Lead distinct from an Event Coordinator', () => {
    // `ec` and `ecl` differ by one letter; a Lead must not land in the
    // coordinator console, nor a coordinator in the Lead's.
    expect(roleFromClaim('ec')).toBe('coordinator')
    expect(roleFromClaim('ecl')).not.toBe(roleFromClaim('ec'))
  })

  it('signs a Lead in from a login response carrying the `ecl` role', () => {
    expect(tokensFromResponse(loginResponse('ecl'))).toMatchObject({
      username: 'ecl1', role: 'coordinator-lead', organisation: 'ConnectSphere',
    })
  })

  it('still treats a role this build does not know as not signed in', () => {
    expect(tokensFromResponse(loginResponse('lead'))).toBeNull()
  })
})
