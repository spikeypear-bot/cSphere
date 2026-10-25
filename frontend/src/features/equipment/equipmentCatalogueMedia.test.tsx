// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup } from '@testing-library/react'
import { EQUIPMENT_MEDIA, equipmentMediaFor } from './equipmentCatalogueMedia'

describe('equipment catalogue media (TS09)', () => {
  afterEach(cleanup)

  it('TS09 (happy): finds a photo and category by catalogue name, ignoring case and spaces', () => {
    const media = equipmentMediaFor('  walkie-talkie ')
    expect(media?.category).toBe('Audiovisual')
    expect(media?.image).toBeTruthy()
    expect(equipmentMediaFor('Mobile Wi-Fi')?.category).toBe('Connectivity')
  })

  it('TS09 (negative): returns undefined for names without a photo', () => {
    expect(equipmentMediaFor('iPad Floor Stand')).toBeUndefined()
    expect(equipmentMediaFor('')).toBeUndefined()
    expect(equipmentMediaFor(undefined)).toBeUndefined()
  })

  it('TS09 (cross-cutting): every photo carries an author, licence and Commons source', () => {
    for (const [name, { credit }] of Object.entries(EQUIPMENT_MEDIA)) {
      expect(credit.author, name).not.toBe('')
      expect(credit.license, name).not.toBe('')
      expect(credit.sourceUrl, name).toMatch(/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/)
      if (credit.license !== 'Public domain') expect(credit.licenseUrl, name).toMatch(/^https:\/\/creativecommons\.org\//)
    }
  })

})
