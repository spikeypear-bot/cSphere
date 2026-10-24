// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { EQUIPMENT_MEDIA, equipmentMediaFor } from './equipmentCatalogueMedia'
import { EquipmentImageCredits } from './EquipmentImageCredits'

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

  it('TS09 (happy): credits list links each requested photo to its source and licence', () => {
    render(<EquipmentImageCredits names={['Projector', 'Power Bank']} />)
    const items = within(screen.getByRole('region', { name: 'Image credits' })).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByRole('link', { name: 'CC BY-SA 4.0' })).toHaveAttribute(
      'href', 'https://creativecommons.org/licenses/by-sa/4.0')
    expect(items[1]).toHaveTextContent('Power Bank: USB power bank by Ronggy, Public domain')
  })
})
