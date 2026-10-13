/// <reference types="node" />
import { afterEach, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const globalCss = readFileSync('src/index.css', 'utf8')
const venueCss = readFileSync('src/features/venueStaff/VenueCataloguePage.css', 'utf8')

afterEach(() => { document.head.querySelectorAll('[data-status-colour-test]').forEach(node => node.remove()); document.body.innerHTML = '' })

it('uses green/red theme colours only for approved/rejected booking labels and pills', () => {
  const style = document.createElement('style')
  style.dataset.statusColourTest = ''
  // JSDOM does not resolve custom properties in stylesheet declarations.
  // Substitute the real light-theme token values before checking the cascade.
  const tokenValue = (token: string) => globalCss.match(new RegExp(`--${token}:\\s*([^;]+);`))![1]
  style.textContent = (globalCss + '\n' + venueCss).replace(/var\(--(success|success-soft|danger|danger-soft|accent-strong|accent-soft)\)/g,
    (_match, token: string) => tokenValue(token))
  document.head.append(style)
  for (const status of ['approved', 'rejected', 'pending', 'changed', 'cancelled']) {
    const label = document.createElement('span')
    label.dataset.bookingStatus = status
    label.className = 'venue-card-preview__tag booking-status-tag'
    document.body.append(label)
    const expected = document.createElement('span')
    const token = status === 'approved' ? 'success' : status === 'rejected' ? 'danger' : 'accent'
    expected.style.color = tokenValue(token === 'accent' ? 'accent-strong' : token)
    expected.style.backgroundColor = tokenValue(`${token}-soft`)
    document.body.append(expected)
    expect(getComputedStyle(label).color).toBe(getComputedStyle(expected).color)
    expect(getComputedStyle(label).backgroundColor).toBe(getComputedStyle(expected).backgroundColor)
    if (status === 'approved' || status === 'rejected') {
      label.className = ''
      expect(getComputedStyle(label).color).toBe(getComputedStyle(expected).color)
    }
  }
})
